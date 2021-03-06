package closures


import dao.hbase.{HBaseDao, HFileUtils}
import global.{AppParams, Configs}
import model.hfile
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.client.Connection
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapreduce.HFileOutputFormat2
import org.apache.hadoop.hbase.{KeyValue, TableName}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import spark.RddLogging
import spark.extensions.sql._

trait BaseClosure extends HFileUtils with Serializable with RddLogging{

  val hbaseDao: HBaseDao = HBaseDao


  def createUnitsHfiles(implicit appconf: AppParams, spark: SparkSession,con:Connection): Unit

  /**
    * Fields:
    * id,BusinessName, UPRN, PostCode,IndustryCode,LegalStatus,
    * TradingStatus, Turnover, EmploymentBands, PayeRefs, VatRefs, CompanyNo
    * */
  def getIncomingBiData(appconf: AppParams)(implicit spark: SparkSession) = {
    val parquetDF = spark.read.parquet(appconf.PATH_TO_PARQUET)
    val rows = parquetDF.castAllToString().rdd.map { row => {

      Row(
        null,
        row.getAs[String]("id"),
        row.getAs[String]("BusinessName"),
        row.getAs[String]("IndustryCode"),
        row.getAs[String]("LegalStatus"),
        row.getValueOrEmptyStr("Address1"),
        row.getAs[String]("Address2"),
        row.getAs[String]("Address3"),
        row.getAs[String]("Address4"),
        row.getAs[String]("Address5"),
        row.getStringValueOrDefault("PostCode", global.Configs.DEFAULT_POSTCODE),
        row.getAs[String]("TradingStatus"),
        row.getAs[String]("Turnover"),
        row.getAs[String]("UPRN"),
        row.getAs[String]("CompanyNo"),
        row.getAs[Seq[String]]("PayeRefs"),
        row.getAs[Seq[String]]("VatRefs")
      )}}
    spark.createDataFrame(rows, biWithErnSchema)

  }

  def getAllLUs(joinedLUs: DataFrame,appconf:AppParams)(implicit spark: SparkSession) = {

    val rows = joinedLUs.rdd.map { row => {
      val ern = if (row.isNull("ern")) generateErn(row, appconf) else row.getAs[String]("ern")

      Row(
        ern,
        row.getAs[String]("ubrn"),
        row.getAs[String]("name"),
        row.getAs[String]("industry_code"),
        row.getAs[String]("legal_status"),
        row.getValueOrEmptyStr("address1"),
        row.getAs[String]("address2"),
        row.getAs[String]("address3"),
        row.getAs[String]("address4"),
        row.getAs[String]("address5"),
        row.getValueOrEmptyStr("postcode"),
        row.getAs[String]("trading_status"),
        row.getAs[String]("turnover"),
        row.getAs[String]("uprn"),
        row.getAs[String]("crn"),
        row.getAs[Seq[String]]("payerefs"),
        row.getAs[Seq[String]]("vatrefs")
      )}}
    spark.createDataFrame(rows, biWithErnSchema)
  }



  def createNewRus(ents: DataFrame, appconf: AppParams)(implicit spark: SparkSession) = {

    spark.createDataFrame(
      ents.rdd.map(row => Row(
        generateRurn(row,appconf),
        row.getAs[String]("ern"),
        row.getAs[String]("name"),
        row.getValueOrNull("entref"),
        row.getValueOrNull("ruref"),
        row.getValueOrNull("trading_style"),
        row.getAs[String]("legal_status"),
        row.getAs[String]("address1"),
        row.getValueOrNull("address2"),
        row.getValueOrNull("address3"),
        row.getValueOrNull("address4"),
        row.getValueOrNull("address5"),
        row.getValueOrEmptyStr("postcode"),
        row.getValueOrEmptyStr("sic07"),
        row.getValueOrEmptyStr("paye_empees"),
        row.getValueOrEmptyStr("turnover"),
        generatePrn(row,appconf),
        row.getValueOrEmptyStr("region"),
        row.getValueOrEmptyStr("employment")
      )), ruRowSchema)
  }


  def createNewEnts(newLEUsCalculatedDF:DataFrame, appconf: AppParams)(implicit spark: SparkSession) = spark.createDataFrame(
    newLEUsCalculatedDF.rdd.map(row => Row(
      row.getAs[String]("ern"),
      generatePrn(row,appconf),
      row.getValueOrNull("entref"),
      row.getAs[String]("BusinessName"),
      null, //trading_style
      row.getValueOrEmptyStr("address1"),
      null, null, null, null, //address2,3,4,5
      row.getAs[String]("PostCode"),
      row.getValueOrEmptyStr("IndustryCode"),
      row.getAs[String]("LegalStatus")
    )
    ), entRowSchema)


  /**
    * Creates new Enterprises from new LEUs with calculations
    * schema: completeNewEntSchema
    * ern, entref, name, address1, postcode, sic07, legal_status,
    * AND calculations:
    * paye_empees, paye_jobs, app_turnover, ent_turnover, cntd_turnover, std_turnover, grp_turnover
    * */
    def createNewEntsWithCalculations(newLEUsCalculatedDF:DataFrame, appconf: AppParams)(implicit spark: SparkSession) = spark.createDataFrame(

      newLEUsCalculatedDF.rdd.map(row => Row(
          row.getAs[String]("ern"),
          generatePrn(row,appconf),
          row.getValueOrNull("entref"),
          row.getAs[String]("name"),
          null, //trading_style
          row.getValueOrEmptyStr("address1"),
          row.getAs[String]("address2"),
          row.getAs[String]("address3"),
          row.getAs[String]("address4"),
          row.getAs[String]("address5"),
          row.getAs[String]("postcode"),
          row.getValueOrEmptyStr("region"),
          row.getValueOrEmptyStr("industry_code"),
          row.getAs[String]("legal_status"),
          row.getValueOrNull("paye_empees"),
          row.getValueOrNull("paye_jobs"),
          row.getValueOrNull("cntd_turnover"),
          row.getValueOrNull("app_turnover"),
          row.getValueOrNull("std_turnover"),
          row.getValueOrNull("grp_turnover"),
          row.getValueOrNull("ent_turnover"),
          row.getStringOption("working_props").getOrElse("0"),
          row.getStringOption("employment").getOrElse("0")
        )), completeEntSchema)



  def createNewLous(rus: DataFrame, appconf: AppParams)(implicit spark: SparkSession) = {

    spark.createDataFrame(
      rus.rdd.map(row => Row(
        generateLurn(row,appconf),
        row.getValueOrNull("luref"),//will not be present
        row.getAs[String]("ern"),
        generatePrn(row,appconf),
        row.getAs[String]("rurn"),
        row.getValueOrNull("ruref"),
        row.getAs[String]("name"),
        row.getValueOrNull("entref"),
        row.getValueOrNull("trading_style"),
        row.getAs[String]("address1"),
        row.getValueOrNull("address2"),
        row.getValueOrNull("address3"),
        row.getValueOrNull("address4"),
        row.getValueOrNull("address5"),
        row.getValueOrEmptyStr("postcode"),
        row.getValueOrEmptyStr("region"),
        row.getValueOrEmptyStr("sic07"),
        row.getValueOrEmptyStr("employees"),
        row.getValueOrEmptyStr("employment")
      )), louRowSchema)
  }


  def getExistingRusDF(appconf: AppParams, confs: Configuration)(implicit spark: SparkSession) = {
    val ruHFileRowRdd: RDD[Row] = hbaseDao.readTable(appconf, confs, HBaseDao.rusTableName(appconf)).map(_.toRuRow)
    spark.createDataFrame(ruHFileRowRdd, ruRowSchema)
  }

  def getExistingLousDF(appconf: AppParams, confs: Configuration)(implicit spark: SparkSession) = {
    val louHFileRowRdd: RDD[Row] = hbaseDao.readTable(appconf, confs, HBaseDao.lousTableName(appconf)).map(_.toLouRow)
    spark.createDataFrame(louHFileRowRdd, louRowSchema)
  }

  def getExistingEntsDF(appconf: AppParams, confs: Configuration)(implicit spark: SparkSession) = {
    val entHFileRowRdd: RDD[Row] = hbaseDao.readTable(appconf,confs, HBaseDao.entsTableName(appconf)).map(_.toEntRow)
    spark.createDataFrame(entHFileRowRdd, entRowSchema)
  }

  /**
    * returns existing LEU~ links DF
    * fields:
    * ubrn, ern, CompanyNo, PayeRefs, VatRefs
    **/
  def getExistingLinksLeusDF(appconf: AppParams, confs: Configuration)(implicit spark: SparkSession) = {
    val leuHFileRowRdd: RDD[Row] = hbaseDao.readTable(appconf,confs, HBaseDao.linksTableName(appconf)).map(_.toLeuLinksRow)
    spark.createDataFrame(leuHFileRowRdd, linksLeuRowSchema)
  }

  def getExistingLeusDF(appconf: AppParams, confs: Configuration)(implicit spark: SparkSession) = {
    val leuHFileRowRdd: RDD[Row] = hbaseDao.readTable(appconf,confs, HBaseDao.leusTableName(appconf)).map(_.toLeuRow)
    spark.createDataFrame(leuHFileRowRdd, leuRowSchema)
  }

  def saveLinks(louDF: DataFrame, ruDF:DataFrame, leuDF: DataFrame, appconf: AppParams)(implicit spark: SparkSession,connection:Connection) = {
    import spark.implicits._

    val tableName = TableName.valueOf(hbaseDao.linksTableName(appconf))
    val regionLocator = connection.getRegionLocator(tableName)
    val partitioner = HFilePartitioner(connection.getConfiguration, regionLocator.getStartKeys, 1)

    val rusLinks: RDD[(String, hfile.HFileCell)] = ruDF.map(row => ruToLinks(row, appconf)).flatMap(identity(_)).rdd
    val lousLinks: RDD[(String, hfile.HFileCell)] = louDF.map(row => louToLinks(row, appconf)).flatMap(identity(_)).rdd
    val restOfLinks: RDD[(String, hfile.HFileCell)] = leuDF.map(row => leuToLinks(row, appconf)).flatMap(identity(_)).rdd

    val allLinks: RDD[((String,String), hfile.HFileCell)] = lousLinks.union(rusLinks).union(restOfLinks).filter(_._2.value!=null).map(entry => ((entry._1,entry._2.qualifier),entry._2) ).repartitionAndSortWithinPartitions(partitioner)
    allLinks.map(rec => (new ImmutableBytesWritable(rec._1._1.getBytes()), rec._2.toKeyValue))
        .saveAsNewAPIHadoopFile(appconf.PATH_TO_LINKS_HFILE, classOf[ImmutableBytesWritable], classOf[KeyValue], classOf[HFileOutputFormat2], Configs.conf)
  }

  def saveEnts(entsDF: DataFrame, appconf: AppParams)(implicit spark: SparkSession) = {
    val hfileCells: RDD[(String, hfile.HFileCell)] = getEntHFileCells(entsDF, appconf)
    hfileCells.filter(_._2.value!=null).sortBy(t => s"${t._2.key}${t._2.qualifier}")
      .map(rec => (new ImmutableBytesWritable(rec._1.getBytes()), rec._2.toKeyValue))
      .saveAsNewAPIHadoopFile(appconf.PATH_TO_ENTERPRISE_HFILE, classOf[ImmutableBytesWritable], classOf[KeyValue], classOf[HFileOutputFormat2], Configs.conf)
  }


  def saveLous(lousDF: DataFrame, appconf: AppParams)(implicit spark: SparkSession) = {
    import spark.implicits._
    val hfileCells = lousDF.map(row => rowToLocalUnit(row, appconf)).flatMap(identity(_)).rdd
    hfileCells.filter(_._2.value!=null).sortBy(t => s"${t._2.key}${t._2.qualifier}")
      .map(rec => (new ImmutableBytesWritable(rec._1.getBytes()), rec._2.toKeyValue))
      .saveAsNewAPIHadoopFile(appconf.PATH_TO_LOCALUNITS_HFILE, classOf[ImmutableBytesWritable], classOf[KeyValue], classOf[HFileOutputFormat2], Configs.conf)
  }


  def saveLeus(leusDF: DataFrame, appconf: AppParams)(implicit spark: SparkSession) = {
    import spark.implicits._
    val hfileCells = leusDF.map(row => rowToLegalUnit(row, appconf)).flatMap(identity(_)).rdd
    hfileCells.filter(_._2.value!=null).sortBy(t => s"${t._2.key}${t._2.qualifier}")
      .map(rec => (new ImmutableBytesWritable(rec._1.getBytes()), rec._2.toKeyValue))
      .saveAsNewAPIHadoopFile(appconf.PATH_TO_LEGALUNITS_HFILE, classOf[ImmutableBytesWritable], classOf[KeyValue], classOf[HFileOutputFormat2], Configs.conf)
  }

  def saveRus(rusDF: DataFrame, appconf: AppParams)(implicit spark: SparkSession) = {
    import spark.implicits._
    val hfileCells = rusDF.map(row => rowToReportingUnit(row, appconf)).flatMap(identity(_)).rdd
    hfileCells.filter(_._2.value!=null).sortBy(t => s"${t._2.key}${t._2.qualifier}")
      .map(rec => (new ImmutableBytesWritable(rec._1.getBytes()), rec._2.toKeyValue))
      .saveAsNewAPIHadoopFile(appconf.PATH_TO_REPORTINGUNITS_HFILE, classOf[ImmutableBytesWritable], classOf[KeyValue], classOf[HFileOutputFormat2], Configs.conf)
  }

  def getEntHFileCells(entsDF: DataFrame, appconf: AppParams)(implicit spark: SparkSession): RDD[(String, hfile.HFileCell)] = {
    import spark.implicits._
    val res: RDD[(String, hfile.HFileCell)] = entsDF.map(row => rowToEnt(row, appconf)).flatMap(identity).rdd
    res
  }


  def calculateRegion(dfWithPostcode:DataFrame, regionsByPostcodeDF:DataFrame, regionsByPostcodeShortDF:DataFrame)(implicit spark: SparkSession) = {
    //dfWithPostcode.withColumn("region",lit(""))
    val partitions = dfWithPostcode.rdd.getNumPartitions
    val step1DF = dfWithPostcode.drop("region")
    val step2DF = step1DF.join(regionsByPostcodeDF, Seq("postcode"),"left_outer")
    val step3DF = step2DF.select("*").where("region IS NULL")
    val partial = step2DF.select("*").where("region IS NOT NULL")
    val step4DF = step3DF.drop("region")
    val step5DF = step4DF.select(col("*"), trim(substring(col("postcode"), 0, (col("postcode").toString().length-4))).as("postcodeout"))
    val step6DF = step5DF.join(regionsByPostcodeShortDF, Seq("postcodeout"),"left_outer")
    val step7DF = step6DF.drop("postcodeout")
    val step8DF = step7DF.union(partial)
    val step9DF = step8DF.na.fill(Configs.DEFAULT_REGION, Seq("region"))
    val step10DF = step9DF.coalesce(partitions)
    step10DF
  }


  def calculateWorkingProps(dfWithLegalStatus:DataFrame)(implicit spark: SparkSession) = {
    import dfWithLegalStatus.sqlContext.implicits.StringToColumn
    import org.apache.spark.sql.functions.udf

    def calculation = udf((legalStatus: String) => getWorkingPropsByLegalStatus(legalStatus))

    dfWithLegalStatus.withColumn("working_props",calculation($"legal_status"))

  }
/**
  * requires working_props and paye_empees recalculated
  * */
  def calculateEmployment(df:DataFrame)(implicit spark: SparkSession) = {

    df.createOrReplaceTempView("CALCULATEEMPLOYMENT")

    val sql =
      """ SELECT *,
                 CAST(
                      (CAST((CASE WHEN paye_empees is NULL THEN 0 ELSE paye_empees END) AS long) + CAST(working_props AS long))
                     AS string) AS employment
          FROM CALCULATEEMPLOYMENT
    """.stripMargin



    spark.sql(sql)
  }
/**
  * expects df with fields 'legal_status', 'postcode', 'paye_empees', 'working_props'
  * */
  def calculateDynamicValues(df:DataFrame, regionsByPostcodeDF:DataFrame, regionsByPostcodeShortDF:DataFrame)(implicit spark: SparkSession) = {
    val withWorkingProps = calculateWorkingProps(df)
    val withEmployment = calculateEmployment(withWorkingProps)
    calculateRegion(withEmployment,regionsByPostcodeDF,regionsByPostcodeShortDF)
  }

}

