package dao.hbase

import global.{AppParams, Configs}
import model.domain.HFileRow
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.hbase.client._
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp
import org.apache.hadoop.hbase.filter.{PrefixFilter, RegexStringComparator, RowFilter}
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapreduce.{HFileOutputFormat2, LoadIncrementalHFiles, TableInputFormat}
import org.apache.hadoop.hbase.protobuf.ProtobufUtil
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos
import org.apache.hadoop.hbase.util.Base64
import org.apache.hadoop.hbase.{KeyValue, TableName}
import org.apache.hadoop.mapreduce.Job
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.slf4j.{Logger, LoggerFactory}

/**
  *
  */
trait HBaseDao extends Serializable {

  import global.Configs._

  val logger: Logger = LoggerFactory.getLogger(getClass)

  def readTable(appParams: AppParams, config: Configuration, tableName: String)
               (implicit spark: SparkSession): RDD[HFileRow] = {

    config.set(TableInputFormat.INPUT_TABLE, tableName)
    val res = readKvsFromHBase(config)
    config.unset(TableInputFormat.INPUT_TABLE)
    res
  }

  def loadHFiles(implicit connection: Connection, appParams: AppParams): Unit = {
    loadLinksHFile
    loadEnterprisesHFile
    loadLousHFile
  }

  def truncateTables(implicit connection: Connection, appParams: AppParams): Unit = {
    truncateLinksTable
    truncateEntsTable
    truncateLousTable
    truncateLeusTable
    truncateRusTable
  }

  def truncateTable(tableName: String)(implicit connection: Connection, appParams: AppParams): Unit =
    wrapTransaction(tableName) { (table, admin) =>
      admin.disableTable(table.getName)
      admin.truncateTable(table.getName, true)
    }

  def truncateLinksTable(implicit connection: Connection, appParams: AppParams): Unit = truncateTable(linksTableName(appParams))

  def truncateEntsTable(implicit connection: Connection, appParams: AppParams): Unit = truncateTable(entsTableName(appParams))

  def truncateLousTable(implicit connection: Connection, appParams: AppParams): Unit = truncateTable(lousTableName(appParams))

  def truncateLeusTable(implicit connection: Connection, appParams: AppParams): Unit = truncateTable(leusTableName(appParams))

  def truncateRusTable(implicit connection: Connection, appParams: AppParams): Unit = truncateTable(rusTableName(appParams))

  def readDeleteData(appParams: AppParams, regex: String)(implicit spark: SparkSession): Unit = {
    val localConfCopy = conf
    val data: RDD[HFileRow] = readLinksWithKeyFilter(localConfCopy, appParams, regex)
    val rows: Array[HFileRow] = data.take(5)
    rows.map(_.toString).foreach(row => print(
      "=" * 10 + row + '\n' + "=" * 10
    ))
  }

  def readLinksWithKeyFilter(confs: Configuration, appParams: AppParams, regex: String)
                            (implicit spark: SparkSession): RDD[HFileRow] = {
    readTableWithKeyFilter(confs, appParams, linksTableName(appParams), regex)
  }

  def readLinksWithKeyPrefixFilter(confs: Configuration, appParams: AppParams, prefix: String)
                                  (implicit spark: SparkSession): RDD[HFileRow] = {
    readTableWithPrefixKeyFilter(confs, appParams, linksTableName(appParams), prefix)
  }

  def readLouWithKeyFilter(confs: Configuration, appParams: AppParams, regex: String)
                          (implicit spark: SparkSession): RDD[HFileRow] = {
    readTableWithKeyFilter(confs, appParams, lousTableName(appParams), regex)
  }

  def readEnterprisesWithKeyFilter(confs: Configuration, appParams: AppParams, regex: String)
                                  (implicit spark: SparkSession): RDD[HFileRow] = {

    readTableWithKeyFilter(confs, appParams, entsTableName(appParams), regex)
  }

  def readTableWithPrefixKeyFilter(confs: Configuration, appParams: AppParams, tableName: String, regex: String)
                                  (implicit spark: SparkSession): RDD[HFileRow] = {
    val localConfCopy = confs
    withKeyPrefixScanner(localConfCopy, regex, appParams, tableName) {
      readKvsFromHBase
    }
  }

  def readTableWithKeyFilter(confs: Configuration, appParams: AppParams, tableName: String, regex: String)
                            (implicit spark: SparkSession): RDD[HFileRow] = {
    val localConfCopy = confs
    withScanner(localConfCopy, regex, appParams, tableName) {
      readKvsFromHBase
    }
  }

  def loadRefreshLinksHFile(implicit connection: Connection, appParams: AppParams): Unit =
    wrapTransaction(linksTableName(appParams)) { (table, admin) =>

      val bulkLoader = new LoadIncrementalHFiles(connection.getConfiguration)
      val regionLocator = connection.getRegionLocator(table.getName)
      bulkLoader.doBulkLoad(new Path(appParams.PATH_TO_LINKS_HFILE), admin, table, regionLocator)
    }

  def loadLinksHFile(implicit connection: Connection, appParams: AppParams): Unit =
    wrapTransaction(linksTableName(appParams)) { (table, admin) =>
      val bulkLoader = new LoadIncrementalHFiles(connection.getConfiguration)
      val regionLocator = connection.getRegionLocator(table.getName)
      bulkLoader.doBulkLoad(new Path(appParams.PATH_TO_LINKS_HFILE), admin, table, regionLocator)
    }

  def loadEnterprisesHFile(implicit connection: Connection, appParams: AppParams): Unit =
    wrapTransaction(entsTableName(appParams)) { (table, admin) =>
      val bulkLoader = new LoadIncrementalHFiles(connection.getConfiguration)
      val regionLocator = connection.getRegionLocator(table.getName)
      bulkLoader.doBulkLoad(new Path(appParams.PATH_TO_ENTERPRISE_HFILE), admin, table, regionLocator)
    }

  def loadLousHFile(implicit connection: Connection, appParams: AppParams): Unit =
    wrapTransaction(lousTableName(appParams)) { (table, admin) =>
      val bulkLoader = new LoadIncrementalHFiles(connection.getConfiguration)
      val regionLocator = connection.getRegionLocator(table.getName)
      bulkLoader.doBulkLoad(new Path(appParams.PATH_TO_LOCALUNITS_HFILE), admin, table, regionLocator)
    }

  def loadLeusHFile(implicit connection: Connection, appParams: AppParams): Unit =
    wrapTransaction(leusTableName(appParams)) { (table, admin) =>
      val bulkLoader = new LoadIncrementalHFiles(connection.getConfiguration)
      val regionLocator = connection.getRegionLocator(table.getName)
      bulkLoader.doBulkLoad(new Path(appParams.PATH_TO_LEGALUNITS_HFILE), admin, table, regionLocator)
    }

  def loadRusHFile(implicit connection: Connection, appParams: AppParams): Unit =
    wrapTransaction(rusTableName(appParams)) { (table, admin) =>
      val bulkLoader = new LoadIncrementalHFiles(connection.getConfiguration)
      val regionLocator = connection.getRegionLocator(table.getName)
      bulkLoader.doBulkLoad(new Path(appParams.PATH_TO_REPORTINGUNITS_HFILE), admin, table, regionLocator)
    }

  private def wrapTransaction(fullTableName: String)(action: (Table, Admin) => Unit)(implicit connection: Connection) {
    val tn = TableName.valueOf(fullTableName)
    val table: Table = connection.getTable(tn)
    val admin = connection.getAdmin
    setJob(table)
    action(table, admin)
    table.close()
  }

  private def wrapReadTransaction(tableName: String)(action: String => RDD[HFileRow])(implicit connection: Connection): RDD[HFileRow] = {
    val table: Table = connection.getTable(TableName.valueOf(tableName))
    val admin = connection.getAdmin
    setJob(table)
    val res = action(tableName)
    table.close()
    res
  }

  private def setJob(table: Table)(implicit connection: Connection) {
    val job = Job.getInstance(connection.getConfiguration)
    job.setMapOutputKeyClass(classOf[ImmutableBytesWritable])
    job.setMapOutputValueClass(classOf[KeyValue])
    HFileOutputFormat2.configureIncrementalLoadMap(job, table)
    //HFileOutputFormat2.configureIncrementalLoad(job, table, connection.getRegionLocator(table.getName))
  }

  def withKeyPrefixScanner(config: Configuration, prefix: String, appParams: AppParams, tableName: String)
                          (getResult: Configuration => RDD[HFileRow]): RDD[HFileRow] = {
    config.set(TableInputFormat.INPUT_TABLE, tableName)
    setPrefixScanner(config, prefix, appParams)
    val res = getResult(config)
    unsetPrefixScanner(config)
    config.unset(TableInputFormat.INPUT_TABLE)
    res
  }

  def withScanner(config: Configuration, regex: String, appParams: AppParams, tableName: String)
                 (getResult: Configuration => RDD[HFileRow]): RDD[HFileRow] = {
    config.set(TableInputFormat.INPUT_TABLE, tableName)
    setScanner(config, regex, appParams)
    val res = getResult(config)
    unsetScanner(config)
    config.unset(TableInputFormat.INPUT_TABLE)
    res
  }

  def readKvsFromHBase(configuration: Configuration)(implicit spark: SparkSession): RDD[HFileRow] = {
    spark.sparkContext.newAPIHadoopRDD(
      configuration,
      classOf[TableInputFormat],
      classOf[org.apache.hadoop.hbase.io.ImmutableBytesWritable],
      classOf[org.apache.hadoop.hbase.client.Result])
      .map(row => HFileRow(row._2))
  }

  def copyExistingRecordsToHFiles(appParams: AppParams, dirName: String = "existing")(implicit spark: SparkSession): Unit = {
    def buildPath(path: String) = {
      val dirs = path.split("/")
      val updatedDirs = (dirs.init :+ dirName) :+ dirs.last
      val res = updatedDirs.mkString("/")
      res
    }

    val prevTimePeriod = {
      (appParams.TIME_PERIOD.toInt - 1).toString
    }

    val ents: RDD[HFileRow] = HBaseDao.readEnterprisesWithKeyFilter(conf, appParams, s"~$prevTimePeriod")
    val links: RDD[HFileRow] = HBaseDao.readLinksWithKeyFilter(conf, appParams, s"~$prevTimePeriod")
    val lous: RDD[HFileRow] = HBaseDao.readLouWithKeyFilter(conf, appParams, s".*~$prevTimePeriod~*.")

    ents.flatMap(_.toHFileCellRow(appParams.HBASE_ENTERPRISE_COLUMN_FAMILY)).sortBy(t => s"${t._2.key}${t._2.qualifier}")
      .map(rec => (new ImmutableBytesWritable(rec._1.getBytes()), rec._2.toKeyValue))
      .saveAsNewAPIHadoopFile(buildPath(appParams.PATH_TO_ENTERPRISE_HFILE), classOf[ImmutableBytesWritable], classOf[KeyValue], classOf[HFileOutputFormat2], Configs.conf)

    links.flatMap(_.toHFileCellRow(appParams.HBASE_LINKS_COLUMN_FAMILY)).sortBy(t => s"${t._2.key}${t._2.qualifier}")
      .map(rec => (new ImmutableBytesWritable(rec._1.getBytes()), rec._2.toKeyValue))
      .saveAsNewAPIHadoopFile(buildPath(appParams.PATH_TO_LINKS_HFILE), classOf[ImmutableBytesWritable], classOf[KeyValue], classOf[HFileOutputFormat2], Configs.conf)

    lous.flatMap(_.toHFileCellRow(appParams.HBASE_LOCALUNITS_COLUMN_FAMILY)).sortBy(t => s"${t._2.key}${t._2.qualifier}")
      .map(rec => (new ImmutableBytesWritable(rec._1.getBytes()), rec._2.toKeyValue))
      .saveAsNewAPIHadoopFile(buildPath(appParams.PATH_TO_LOCALUNITS_HFILE), classOf[ImmutableBytesWritable], classOf[KeyValue], classOf[HFileOutputFormat2], Configs.conf)

  }
  private def unsetScanner(config: Configuration): Unit = config.unset(TableInputFormat.SCAN)

  private def setScanner(config: Configuration, regex: String, appParams: AppParams): Unit = {

    val comparator = new RegexStringComparator(regex)
    val filter = new RowFilter(CompareOp.EQUAL, comparator)

    val scan = new Scan()
    scan.setFilter(filter)

    val scanStr = convertScanToString(scan)

    config.set(TableInputFormat.SCAN, scanStr)
  }

  private def setPrefixScanner(config: Configuration, prefix: String, appParams: AppParams): Unit = {

    val prefixFilter = new PrefixFilter(prefix.getBytes)

    val scan: Scan = new Scan()
    scan.setFilter(prefixFilter)

    val scanStr = convertScanToString(scan)
    config.set(TableInputFormat.SCAN, scanStr)
  }

  private def convertScanToString(scan: Scan): String = {
    val proto: ClientProtos.Scan = ProtobufUtil.toScan(scan)
    Base64.encodeBytes(proto.toByteArray)
  }

  private def unsetPrefixScanner(config: Configuration): Unit = config.unset(TableInputFormat.SCAN)

  def linksTableName(appconf: AppParams) = s"${appconf.HBASE_LINKS_TABLE_NAMESPACE}:${appconf.HBASE_LINKS_TABLE_NAME}_${appconf.TIME_PERIOD}"

  def leusTableName(appconf: AppParams) = s"${appconf.HBASE_LEGALUNITS_TABLE_NAMESPACE}:${appconf.HBASE_LEGALUNITS_TABLE_NAME}_${appconf.TIME_PERIOD}"

  def lousTableName(appconf: AppParams) = s"${appconf.HBASE_LOCALUNITS_TABLE_NAMESPACE}:${appconf.HBASE_LOCALUNITS_TABLE_NAME}_${appconf.TIME_PERIOD}"

  def rusTableName(appconf: AppParams) = s"${appconf.HBASE_REPORTINGUNITS_TABLE_NAMESPACE}:${appconf.HBASE_REPORTINGUNITS_TABLE_NAME}_${appconf.TIME_PERIOD}"

  def entsTableName(appconf: AppParams) = s"${appconf.HBASE_ENTERPRISE_TABLE_NAMESPACE}:${appconf.HBASE_ENTERPRISE_TABLE_NAME}_${appconf.TIME_PERIOD}"

}

object HBaseDao extends HBaseDao