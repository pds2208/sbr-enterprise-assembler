package dao.parquet

import dao.hbase.HBaseDao
import dao.hbase.converter.WithConversionHelper
import global.{AppParams, Configs}
import model.domain.HFileRow
import model.hfile
import org.apache.hadoop.hbase.KeyValue
import org.apache.hadoop.hbase.client.Connection
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapreduce.HFileOutputFormat2
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.apache.spark.sql.{Row, SparkSession}
import org.slf4j.LoggerFactory
import spark.calculations.DataFrameHelper
import spark.extensions.sql.SqlRowExtensions

trait ParquetDao extends WithConversionHelper with DataFrameHelper with Serializable{

  val logger = LoggerFactory.getLogger(getClass)

  def jsonToParquet(jsonFilePath:String)(implicit spark:SparkSession,appconf:AppParams) = spark.read.json(jsonFilePath).write.parquet(appconf.PATH_TO_PARQUET)

  def parquetCreateNewToHFile(implicit spark:SparkSession, appconf:AppParams){

    val appArgs = appconf

    val payeDF = spark.read.option("header", "true").csv(appconf.PATH_TO_PAYE)
    val vatDF  = spark.read.option("header", "true").csv(appconf.PATH_TO_VAT)

    val parquetRDD: RDD[hfile.Tables] = adminCalculations(spark.read.parquet(appArgs.PATH_TO_PARQUET), payeDF, vatDF).rdd.map(row => toNewEnterpriseRecordsWithLou(row,appArgs)).cache()

        parquetRDD.flatMap(_.links).sortBy(t => s"${t._2.key}${t._2.qualifier}")
          .map(rec => (new ImmutableBytesWritable(rec._1.getBytes()), rec._2.toKeyValue))
              .saveAsNewAPIHadoopFile(appconf.PATH_TO_LINKS_HFILE,classOf[ImmutableBytesWritable],classOf[KeyValue],classOf[HFileOutputFormat2],Configs.conf)

        parquetRDD.flatMap(_.enterprises).sortBy(t => s"${t._2.key}${t._2.qualifier}")
          .map(rec => (new ImmutableBytesWritable(rec._1.getBytes()), rec._2.toKeyValue))
              .saveAsNewAPIHadoopFile(appconf.PATH_TO_ENTERPRISE_HFILE,classOf[ImmutableBytesWritable],classOf[KeyValue],classOf[HFileOutputFormat2],Configs.conf)

         parquetRDD.flatMap(_.localUnits).sortBy(t => s"${t._2.key}${t._2.qualifier}")
          .map(rec => (new ImmutableBytesWritable(rec._1.getBytes()), rec._2.toKeyValue))
              .saveAsNewAPIHadoopFile(appconf.PATH_TO_LOCALUNITS_HFILE,classOf[ImmutableBytesWritable],classOf[KeyValue],classOf[HFileOutputFormat2],Configs.conf)



        parquetRDD.unpersist()
  }

    def readParquet(appconf:AppParams)(implicit spark:SparkSession)  = {
      val parquetRDD: RDD[(String, hfile.HFileCell)] = spark.read.parquet(appconf.PATH_TO_PARQUET).rdd.flatMap(row => toLinksRefreshRecords(row,appconf))
      parquetRDD
    }


    def readParquetIntoHFileRow(appconf:AppParams)(implicit spark:SparkSession)  = {
      val parquetRDD: RDD[(String, hfile.HFileCell)] = spark.read.parquet(appconf.PATH_TO_PARQUET).rdd.flatMap(row => toLinksRefreshRecords(row,appconf))
      parquetRDD
    }



    def createRefreshLinksHFile(appconf:AppParams)(implicit spark:SparkSession) = {


      val parquetRDD: RDD[(String, hfile.HFileCell)] = spark.read.parquet(appconf.PATH_TO_PARQUET).rdd.flatMap(row => toLinksRefreshRecords(row,appconf))

      parquetRDD.sortBy(t => s"${t._2.key}${t._2.qualifier}")
        .map(rec => (new ImmutableBytesWritable(rec._1.getBytes()), rec._2.toKeyValue))
        .saveAsNewAPIHadoopFile(appconf.PATH_TO_LINKS_HFILE_UPDATE, classOf[ImmutableBytesWritable], classOf[KeyValue], classOf[HFileOutputFormat2], Configs.conf)
      }



    def createEnterpriseRefreshHFile(appconf:AppParams)(implicit spark:SparkSession,connection:Connection) = {
            val localConfigs = Configs.conf
            val regex = "~LEU~"+{appconf.TIME_PERIOD}+"$"
            val lus: RDD[HFileRow] = HBaseDao.readLinksWithKeyFilter(localConfigs,appconf,regex) //read LUs from links

            val rows: RDD[Row] = lus.map(row => Row(row.getId, row.cells.find(_.column == "p_ENT").get.value)) //extract ERNs

            val schema = new StructType()
              .add(StructField("id", StringType, true))
              .add(StructField("ern", StringType, true))

            val erns = spark.createDataFrame(rows,schema)

            val refreshDF = spark.read.parquet(appconf.PATH_TO_PARQUET)

            val fullLUs = refreshDF.join(erns,"id")

            val payeDF = spark.read.option("header", "true").csv(appconf.PATH_TO_PAYE)
            val vatDF  = spark.read.option("header", "true").csv(appconf.PATH_TO_VAT)

            //get cells for jobs and employees - the only updateable columns in enterprise table
            val entsRDD: RDD[(String, hfile.HFileCell)] = adminCalculations(fullLUs, payeDF, vatDF).rdd.flatMap(row => Seq(
              ParquetDao.createEnterpriseCell(row.getString("ern").get,"paye_employees",row.getCalcValue("paye_employees").get,appconf),
              ParquetDao.createEnterpriseCell(row.getString("ern").get,"paye_jobs",row.getCalcValue("paye_jobs").get,appconf)
            ))

            entsRDD.sortBy(t => s"${t._2.key}${t._2.qualifier}").map(rec => (new ImmutableBytesWritable(rec._1.getBytes()), rec._2.toKeyValue))
              .saveAsNewAPIHadoopFile(appconf.PATH_TO_ENTERPRISE_HFILE,classOf[ImmutableBytesWritable],classOf[KeyValue],classOf[HFileOutputFormat2],Configs.conf)

    }


}
object ParquetDao extends ParquetDao