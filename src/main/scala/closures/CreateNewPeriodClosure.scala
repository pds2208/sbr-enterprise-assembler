package closures

import dao.hbase.HBaseDao
import dao.hbase.converter.WithConversionHelper
import global.{AppParams, Configs}
import model.domain.{HFileRow, KVCell}
import model.hfile
import model.hfile.HFileCell
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.KeyValue
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapreduce.HFileOutputFormat2
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import spark.calculations.DataFrameHelper
import spark.extensions.sql._

import scala.util.Try




object CreateNewPeriodClosure extends WithConversionHelper with DataFrameHelper{


  type Cells = Iterable[KVCell[String, String]]
  type Record = (String, Cells)
  /**
    * copies data from Enterprise and Local Units tables into a new period data
    **/
  def createNewPeriodHfiles(confs: Configuration, appParams: AppParams)(implicit spark: SparkSession): Unit = {
    //do enterprise. Local units to follow
    saveEnterpriseHFiles(confs, appParams, ".*ENT~"+{appParams.PREVIOUS_TIME_PERIOD}+"$") //.*(~201802)$ //.*(?!~ENT~)201802$
    saveLinksHFiles(confs, appParams, ".*(~ENT~"+{appParams.PREVIOUS_TIME_PERIOD}+")$")
  }


  def addNewPeriodData(appconf: AppParams)(implicit spark: SparkSession) = {

    val confs = Configs.conf

    val updatedConfs = appconf.copy(TIME_PERIOD=appconf.PREVIOUS_TIME_PERIOD) //set period to previous to make join possible
    val parquetRows: RDD[Row] = spark.read.parquet(appconf.PATH_TO_PARQUET).rdd
    val linksRecords: RDD[(String, HFileCell)] = parquetRows.flatMap(row => toLinksRefreshRecords(row,appconf))
    val updatesRdd: RDD[Record] = linksRecords.groupByKey().map(v => (v._1,v._2.map(kv => KVCell[String,String](kv.qualifier,kv.value))))//get LINKS updates from input parquet

    //next 3 lines: select LU rows from hbase
    val linksTableName = s"${appconf.HBASE_LINKS_TABLE_NAMESPACE}:${appconf.HBASE_LINKS_TABLE_NAME}"
    val luRegex = ".*(ENT|LEU)~"+{appconf.PREVIOUS_TIME_PERIOD}+"$"
    val existingLuRdd: RDD[Record] = HBaseDao.readTableWithKeyFilter(confs,appconf, linksTableName, luRegex).map(row => (row.key.replace(s"~${appconf.PREVIOUS_TIME_PERIOD}",s"~${appconf.TIME_PERIOD}"),row.cells))

    val numOfPartitions = updatesRdd.getNumPartitions
    val joined: RDD[(String, (Option[Cells], Option[Cells]))] = updatesRdd.fullOuterJoin(existingLuRdd, numOfPartitions)

    val updatedExistingLUs: RDD[HFileRow] = joined.collect { case (key, (Some(newCells), Some(oldCells))) => HFileRow(key,{newCells ++ oldCells.find(_.column=="p_ENT").map(ernCell => Seq(ernCell)).getOrElse(Seq.empty) })} // existing LUs updated with new cells

    val newLUs: RDD[HFileRow] = joined.collect { case (key, (Some(newCells), None)) => HFileRow(key, newCells) }

    val existingLinksEnts: RDD[HFileRow] = joined.collect { case (key, (None, Some(oldCells))) if(key.endsWith(s"ENT~${appconf.TIME_PERIOD}"))=> HFileRow(key, oldCells) }



    //new Records
    val newLuIds: RDD[(Long, Row)] = newLUs.filter(_.key.endsWith(s"~LEU~${appconf.TIME_PERIOD}")).collect{case HFileRow(key,_) if(key.endsWith(s"~${appconf.TIME_PERIOD}")) => (key.stripSuffix(s"~LEU~${appconf.TIME_PERIOD}").toLong,Row.empty)}

    val rowMapByKey: RDD[(Long, Row)] = parquetRows.map(row => (row.getLong("id").get, row))

    val joinedParquetRows = newLuIds.leftOuterJoin(rowMapByKey,numOfPartitions)

    val newLUParquetRows: RDD[Row] = joinedParquetRows.collect{  case (key,(oldRow,Some(newRow))) => newRow }

    val newRowsDf: DataFrame = spark.createDataFrame(newLUParquetRows,parquetRowSchema)

    val newEntTree: RDD[hfile.Tables] = finalCalculations(newRowsDf, spark.read.option("header", "true").csv(appconf.PATH_TO_PAYE)).rdd.map(row => toEnterpriseRecords(row,appconf))
    val res = newEntTree.collect()
    val newEnts: RDD[(String, HFileCell)] =  newEntTree.flatMap(_.enterprises) //break into cells
    val newLinks: RDD[(String, HFileCell)] =  newEntTree.flatMap(_.links) //break into cells


    //existing records:
    val entRegex = ".*~"+{appconf.PREVIOUS_TIME_PERIOD}+"$"
    val entTableName = s"${appconf.HBASE_ENTERPRISE_TABLE_NAMESPACE}:${appconf.HBASE_ENTERPRISE_TABLE_NAME}"
    val existingEntRdd: RDD[Row] = HBaseDao.readTableWithKeyFilter(confs:Configuration,appconf:AppParams, entTableName, entRegex).map(_.toEntRow)

    val entDF: DataFrame = spark.createDataFrame(existingEntRdd,entRowSchema) //ENT record DF

    val luRows: RDD[Row] = updatedExistingLUs.map(_.toLuRow)//.map(row => row.copy())
    val luDF: DataFrame = spark.createDataFrame(luRows,luRowSchema)
    val payeDF: DataFrame = spark.read.option("header", "true").csv(appconf.PATH_TO_PAYE)
    val calculated: DataFrame = finalCalculations(luDF,payeDF,"ubrn")
    val calc2: DataFrame = calculated.drop(calculated.columns.filterNot(c => Seq("ern","paye_employees","paye_jobs").contains(c)): _*)

    val entWithEmployee: DataFrame = entDF.join(calc2,"ern").coalesce(numOfPartitions)
    val entSqlRows = entWithEmployee.rdd.map(df => new GenericRowWithSchema(Array(
                                                   Try{df.getAs[String]("ern")}.getOrElse(""),
                                                   Try{df.getAs[String]("idbrref")}.getOrElse(""),
                                                   Try{df.getAs[String]("name")}.getOrElse(""),
                                                   Try{df.getAs[String]("tradingstyle")}.getOrElse(""),
                                                   Try{df.getAs[String]("address1")}.getOrElse(""),
                                                   Try{df.getAs[String]("address2")}.getOrElse(""),
                                                   Try{df.getAs[String]("address3")}.getOrElse(""),
                                                   Try{df.getAs[String]("address4")}.getOrElse(""),
                                                   Try{df.getAs[String]("address5")}.getOrElse(""),
                                                   Try{df.getAs[String]("postcode")}.getOrElse(""),
                                                   Try{df.getAs[String]("legalstatus")}.getOrElse(""),
                                                   Try{df.getAs[Int]("paye_employees")}.map(_.toString).getOrElse(""),
                                                   Try{df.getAs[Long]("paye_jobs")}.map(_.toString).getOrElse("")
                                                 ),entRowWithEmplDataSchema))
    /**
      * add new + existing enterprises and save to hfile
      * */

    val hfileRdd: RDD[(String, HFileCell)] = entSqlRows.flatMap(row => rowToEnterprise(row,appconf))
    val allEnts = newEnts.union(hfileRdd).coalesce(numOfPartitions)

    allEnts.sortBy(t => s"${t._2.key}${t._2.qualifier}")
      .map(rec => (new ImmutableBytesWritable(rec._1.getBytes()), rec._2.toKeyValue))
      .saveAsNewAPIHadoopFile(appconf.PATH_TO_ENTERPRISE_HFILE,classOf[ImmutableBytesWritable],classOf[KeyValue],classOf[HFileOutputFormat2],Configs.conf)


/** 
  * add new + existing links and save to hfile
  * */

  val existingLusCells: RDD[(String, HFileCell)] = luRows.flatMap(r => rowToLegalUnitLinks("ubrn",r,appconf))

  val allLus: RDD[(String, HFileCell)] = existingLusCells.union(newLinks).coalesce(numOfPartitions)

  allLus.sortBy(t => s"${t._2.key}${t._2.qualifier}")
      .map(rec => (new ImmutableBytesWritable(rec._1.getBytes()), rec._2.toKeyValue))
      .saveAsNewAPIHadoopFile(appconf.PATH_TO_LINKS_HFILE,classOf[ImmutableBytesWritable],classOf[KeyValue],classOf[HFileOutputFormat2],Configs.conf)
 }

  private def saveEnterpriseHFiles(confs: Configuration, appParams: AppParams, regex: String)(implicit spark: SparkSession) = {
    HBaseDao.readEnterprisesWithKeyFilter(confs, appParams, regex)
      .map(row => row.copy(row.key.replace(s"~${appParams.PREVIOUS_TIME_PERIOD}",s"~${appParams.TIME_PERIOD}")))
      .sortBy(row => s"${row.key}")
      .flatMap(_.toPutHFileEntries(appParams.HBASE_ENTERPRISE_COLUMN_FAMILY))
      .saveAsNewAPIHadoopFile(appParams.PATH_TO_ENTERPRISE_HFILE, classOf[ImmutableBytesWritable], classOf[KeyValue], classOf[HFileOutputFormat2], confs)
  }

  private def saveLinksHFiles(confs: Configuration, appParams: AppParams, regex: String)(implicit spark: SparkSession) = {
    HBaseDao.readLinksWithKeyFilter(confs, appParams, regex)
      .map(row => row.copy(row.key.replace(s"~${appParams.PREVIOUS_TIME_PERIOD}",s"~${appParams.TIME_PERIOD}")))
      .sortBy(row => s"${row.key}")
      .flatMap(_.toPutHFileEntries(appParams.HBASE_LINKS_COLUMN_FAMILY))
      .saveAsNewAPIHadoopFile(appParams.PATH_TO_LINKS_HFILE, classOf[ImmutableBytesWritable], classOf[KeyValue], classOf[HFileOutputFormat2], confs)
  }


}

