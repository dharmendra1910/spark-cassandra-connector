/**
  * Copyright DataStax, Inc.
  *
  * Please see the included license file for details.
  */
package com.datastax.spark.connector.rdd

import java.io.IOException

import scala.collection.JavaConversions._
import scala.language.existentials
import scala.reflect.ClassTag

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.metrics.InputMetricsUpdater
import org.apache.spark.rdd.RDD
import org.apache.spark.{Partition, SparkContext, TaskContext}

import com.datastax.driver.core._
import com.datastax.spark.connector.CassandraRowMetadata
import com.datastax.spark.connector.cql.{CassandraConnector, ColumnDef, Schema}
import com.datastax.spark.connector.rdd.partitioner.{CassandraPartition, CqlTokenRange, NodeAddresses}
import com.datastax.spark.connector.rdd.reader.{PrefetchingResultSetIterator, RowReader}
import com.datastax.spark.connector.types.ColumnType
import com.datastax.spark.connector.util.Quote._
import com.datastax.spark.connector.util.{CountingIterator, MergeJoinIterator, NameTools}

/**
  * A RDD which pulls from two separate CassandraTableScanRDDs which share partition keys and
  * keyspaces. These tables will be joined on READ using a merge iterator. As long as we join
  * on the token of the partition key the two iterators should be read in order.
  */
class CassandraMergeJoinRDD[L,R](
    sc: SparkContext,
    leftScanRDD: CassandraTableScanRDD[L],
    rightScanRDD: CassandraTableScanRDD[R])(
  implicit
    leftClassTag: ClassTag[L],
    rightClassTag: ClassTag[R])
  extends RDD[(Seq[L], Seq[R])](sc, Seq.empty){

  val connector = leftScanRDD.connector

  def getPartitionKey(connector: CassandraConnector, keyspaceName: String, tableName: String): Seq[ColumnDef] = {
    Schema.fromCassandra(connector, Some(keyspaceName), Some(tableName)).tables.headOption match {
      case Some(table) => table.partitionKey
      case None => {
        val metadata: Metadata = connector.withClusterDo(_.getMetadata)
        val suggestions = NameTools.getSuggestions(metadata, keyspaceName, tableName)
        val errorMessage = NameTools.getErrorString(keyspaceName, tableName, suggestions)
        throw new IOException(errorMessage)
      }
    }
  }

  def checkValidMergeJoin() {
    val leftPartitionKeyTypes = getPartitionKey(
      leftScanRDD.connector,
      leftScanRDD.keyspaceName,
      leftScanRDD.tableName)
      .map(_.columnType)

    val rightPartitionKeyTypes = getPartitionKey(
      rightScanRDD.connector,
      rightScanRDD.keyspaceName,
      rightScanRDD.tableName)
      .map(_.columnType)

    assert( leftPartitionKeyTypes == rightPartitionKeyTypes,
      "Partition key types do not match between Right and Left RDDs in MergeJoin")
  }

  val TokenColumn = "mj_tok_col"
  def tokenExtractor (row: Row): Token = {
    row.getToken(TokenColumn)
  }

  private lazy val leftPartitionKeyStr =
    getPartitionKey(connector, leftScanRDD.keyspaceName, leftScanRDD.tableName)
      .map(_.columnName)
      .map(quote)
      .mkString(", ")

  private def tokenRangeToCqlQuery[T](
    fromRDD: CassandraTableScanRDD[T],
    range: CqlTokenRange[_, _]): (String, Seq[Any]) = {

    val columns = fromRDD.selectedColumnRefs.map(_.cql).mkString(", ")
    val pk = fromRDD.tableDef.partitionKey.map(colDef => quote(colDef.columnName)).mkString(",")
    val (cql, values) = range.cql(leftPartitionKeyStr)
    val filter = (cql +: fromRDD.where.predicates).filter(_.nonEmpty).mkString(" AND ")
    val limitClause = fromRDD.limit.map(limit => s"LIMIT $limit").getOrElse("")
    val orderBy = fromRDD.clusteringOrder.map(_.toCql(fromRDD.tableDef)).getOrElse("")
    val quotedKeyspaceName = quote(fromRDD.keyspaceName)
    val quotedTableName = quote(fromRDD.tableName)
    val queryTemplate =
      s"SELECT $columns, TOKEN($pk) as $TokenColumn " +
        s"FROM $quotedKeyspaceName.$quotedTableName " +
        s"WHERE $filter $orderBy $limitClause ALLOW FILTERING"
    val queryParamValues = values ++ fromRDD.where.values
    (queryTemplate, queryParamValues)
  }

  private def createStatement(
    session: Session,
    readConf: ReadConf,
    cql: String,
    values: Any*): Statement = {

    try {
      val stmt = session.prepare(cql)
      stmt.setConsistencyLevel(readConf.consistencyLevel)
      val converters = stmt.getVariables
        .map(v => ColumnType.converterToCassandra(v.getType))
        .toArray
      val convertedValues =
        for ((value, converter) <- values zip converters)
          yield converter.convert(value)
      val bstm = stmt.bind(convertedValues: _*)
      bstm.setFetchSize(readConf.fetchSizeInRows)
      bstm
    }
    catch {
      case t: Throwable =>
        throw new IOException(s"Exception during preparation of $cql: ${t.getMessage}", t)
    }
  }

  private def convertRowIterator[T](
      iterator: Iterator[Row],
      rowReader: RowReader[T],
      columnMetaData: CassandraRowMetadata): Iterator[T] = {

    iterator.map{ row =>
      rowReader.read(row, columnMetaData)
    }
  }

  private def fetchTokenRange[T](
    session: Session,
    fromRDD: CassandraTableScanRDD[T],
    range: CqlTokenRange[_, _],
    inputMetricsUpdater: InputMetricsUpdater): (CassandraRowMetadata, Iterator[Row]) = {

    val (cql, values) = tokenRangeToCqlQuery[T](fromRDD, range)
    logDebug(
      s"Fetching data for range ${range} " +
        s"with $cql " +
        s"with params ${values.mkString("[", ",", "]")}")
    val stmt = createStatement(session, fromRDD.readConf, cql, values: _*)

    try {
      val rs = session.execute(stmt)
      val columnNames = fromRDD.selectedColumnRefs.map(_.selectedAs).toIndexedSeq ++ Seq(TokenColumn)
      val columnMetaData = CassandraRowMetadata.fromResultSet(columnNames, rs, session)
      val iterator = new PrefetchingResultSetIterator(rs, fromRDD.readConf.fetchSizeInRows)
      val iteratorWithMetrics = iterator.map(inputMetricsUpdater.updateMetrics)
      logDebug(s"Row iterator for range ${range} obtained successfully.")
      (columnMetaData, iteratorWithMetrics)
    } catch {
      case t: Throwable =>
        throw new IOException(s"Exception during execution of $cql: ${t.getMessage}", t)
    }
  }


  @DeveloperApi
  override def compute(split: Partition, context: TaskContext): Iterator[(Seq[L], Seq[R])] = {

    /** Open two sessions if Cluster Configurations are different **/
    def openSessions(): (Session, Session) = {
      if (leftScanRDD.connector == rightScanRDD.connector) {
        val session = leftScanRDD.connector.openSession()
        (session, session)
      } else {
        (leftScanRDD.connector.openSession, rightScanRDD.connector.openSession)
      }
    }

    def closeSessions(leftSession: Session, rightSession : Session): Unit = {
      if (leftSession != rightSession) rightSession.close()
      leftSession.close()
    }

    val (leftSession, rightSession) = openSessions()

    type V = t forSome { type t }
    type T = t forSome { type t <: com.datastax.spark.connector.rdd.partitioner.dht.Token[V] }
    val partition = split.asInstanceOf[CassandraPartition[V, T]]
    val tokenRanges = partition.tokenRanges

    val metricsReadConf = new ReadConf(taskMetricsEnabled =
      leftScanRDD.readConf.taskMetricsEnabled || rightScanRDD.readConf.taskMetricsEnabled)

    val metricsUpdater = InputMetricsUpdater(context, metricsReadConf)

    val mergingIterator = tokenRanges.iterator.flatMap { tokenRange =>
      val (leftMetadata, leftRowIterator) = fetchTokenRange(leftSession, leftScanRDD, tokenRange, metricsUpdater)
      val (rightMetadata, rightRowIterator) = fetchTokenRange(rightSession, rightScanRDD, tokenRange, metricsUpdater)

      val rowMerger = new MergeJoinIterator[Row, Row, Token](
        leftRowIterator,
        rightRowIterator,
        tokenExtractor,
        tokenExtractor
      )
      rowMerger.map { case (t: Token, lRows : Seq[Row], rRows: Seq[Row]) => (
        t,
        convertRowIterator(lRows.iterator, leftScanRDD.rowReader, leftMetadata).toList,
        convertRowIterator(rRows.iterator, rightScanRDD.rowReader, rightMetadata).toList)
      }
    }

    val countingIterator = new CountingIterator(mergingIterator)

    context.addTaskCompletionListener { (context) =>
      val duration = metricsUpdater.finish() / 1000000000d
      logDebug(
        f"""Fetched ${countingIterator.count} rows from
            |${leftScanRDD.keyspaceName} ${leftScanRDD.tableName} and ${rightScanRDD.tableName}
            |for partition ${partition.index} in $duration%.3f s.""".stripMargin)
      closeSessions(leftSession, rightSession)
    }
    val iteratorWithoutToken = countingIterator.map(tuple => (tuple._2, tuple._3))
    iteratorWithoutToken
  }

  override protected def getPartitions: Array[Partition] = {
    checkValidMergeJoin()
    if (leftScanRDD.partitions.size >= rightScanRDD.partitions.size)
      leftScanRDD.partitions else rightScanRDD.partitions
  }

  private lazy val nodeAddresses = new NodeAddresses(connector)
  override def getPreferredLocations(split: Partition): Seq[String] =
    split.asInstanceOf[CassandraPartition[_, _]].endpoints.flatMap(nodeAddresses.hostNames).toSeq

}