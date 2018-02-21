package dao.hbase.converter



import global.Configs
import model._
import org.apache.spark.sql.Row

import scala.util.{Random, Success, Try}

/**
* Schema:
* index | fields
* -------------------
*  0 -   BusinessName: string (nullable = true)
*  1 -   CompanyNo: string (nullable = true)
*  2 -   EmploymentBands: string (nullable = true)
*  3 -   IndustryCode: string (nullable = true)
*  4 -   LegalStatus: string (nullable = true)
*  5 -   PayeRefs: array (nullable = true)
*  6 -   PostCode: string (nullable = true)
*  7 -   TradingStatus: string (nullable = true)
*  8 -   Turnover: string (nullable = true)
*  9 -   UPRN: long (nullable = true)
*  10 -  VatRefs: array (nullable = true)
*  11 -  id: long (nullable = true)
  */
trait WithConvertionHelper {
/*
* Rules:
* fields needed for crating ENTERPRISE UNIT:
* 1. ID(UBRN) - NOT NULL
* ## At least one of the below must be present
* 2. PayeRefs  - NULLABLE
* 3. VatRefs - NULLABLE
* 4. CompanyNo - NULLABLE
* */

  import Configs._

  val period = "201802"

  //def printRow(r:Row) =  (0 to 11).foreach(v => println(s"index: $v, name: ${r.schema.fields(v).name}, value: ${Try {r.get(v).toString} getOrElse "NULL"}"))

  def toRecords(row:Row): Tables = {
    val ern = generateErn
    Tables(rowToEnterprise(row,ern),rowToLinks(row,ern))
  }

  private def isNull(row:Row, field:String) = row.isNullAt(row.fieldIndex(field))
  private def getValue[T](row:Row, fieldName:String)( eval:(Row,String) => Boolean = isNull) = if(eval(row, fieldName)) None else Some(row.getAs[T](fieldName))

  private def getStringValue(row:Row, fieldName:String) = getValue[String](row,fieldName){(row,fieldName) => isNull(row,fieldName) || row.getAs[String](fieldName).trim.isEmpty}

  private def getSeq[T](row:Row, fieldName:String, eval:T => Boolean): Option[Seq[T]] = if(isNull(row,fieldName)) None else Some(row.getSeq[T](row.fieldIndex(fieldName)).collect{ case v if(v!=null && eval(v)) => v})
  private def getSeq[T](row:Row, fieldName:String): Option[Seq[T]] = if(isNull(row,fieldName)) None else Some(row.getSeq[T](row.fieldIndex(fieldName)))

  private def rowToEnterprise(row:Row,ern:String): Seq[(String, RowObject)] = Seq(createEnterpriseRecord(ern,"ern",ern), createEnterpriseRecord(ern,"idbrref","9999999999"))++
        Seq(
          getStringValue(row, "BusinessName").map(bn  => createEnterpriseRecord(ern,"name",bn)),
          getStringValue(row, "PostCode")map(pc => createEnterpriseRecord(ern,"postcode",pc)),
          getStringValue(row, "LegalStatus").map(ls => createEnterpriseRecord(ern,"legalstatus",ls))
        ).collect{case Some(v) => v}



  private def rowToLinks(row:Row,ern:String): Seq[(String, RowObject)] = {
      //printRow(row)
      val ubrn = row.getAs[Long]("id")
      val keyStr = generateKey(ern,"ENT")
      createLinksRecord(keyStr,s"C:$ubrn","legalunit")+:rowToLegalUnitLinks(row,ern)
    }


  private def rowToLegalUnitLinks(row:Row, ern:String):Seq[(String, RowObject)] = {

      val ubrn: String = row.getAs[Long](("id")).toString
      val luKey = generateKey(ubrn,"LEU")
      createLinksRecord(luKey,s"P:$ern","enterprise") +: (rowToCHLinks(row,luKey,ubrn) ++ rowToVatRefsLinks(row,luKey,ubrn) ++ rowToPayeRefLinks(row,luKey,ubrn))
    }

  private def rowToCHLinks(row:Row, luKey:String, ubrn:String):Seq[(String, RowObject)] = getStringValue(row,"CompanyNo").map(companyNo => Seq(
      createLinksRecord(luKey,s"C:$companyNo","ch"),
      createLinksRecord(generateKey(companyNo,"CH"),s"P:$ubrn","legalunit")
    )).getOrElse(Seq[(String, RowObject)]())


  private def rowToVatRefsLinks(row:Row, luKey:String, ubrn:String):Seq[(String, RowObject)] = getSeq[Long](row,"VatRefs").map(_.flatMap(vat => Seq(
        createLinksRecord(luKey,s"C:$vat","vat"),
        createLinksRecord(generateKey(vat.toString,"VAT"),s"P:${ubrn.toString}","legalunit")
      ))).getOrElse (Seq[(String, RowObject)]())



  private def rowToPayeRefLinks(row:Row, luKey:String, ubrn:String):Seq[(String, RowObject)] = getSeq(row,"PayeRefs", (pr:String) => {pr.trim.nonEmpty}).map(_.flatMap(paye => Seq(
        createLinksRecord(luKey,s"C:$paye","paye"),
        createLinksRecord(generateKey(paye,"PAYE"),s"P:${ubrn.toString}","legalunit")
      ))).getOrElse(Seq[(String, RowObject)]())



  private def createLinksRecord(key:String,column:String, value:String) = createRecord(key,HBASE_LINKS_COLUMN_FAMILY,column,value)

  private def createEnterpriseRecord(ern:String,column:String, value:String) = createRecord(s"${ern.reverse}~$period",HBASE_ENTERPRISE_COLUMN_FAMILY,column,value)


  private def createRecord(key:String,columnFamily:String, column:String, value:String) = key -> RowObject(key,columnFamily,column,value)

  private def generateErn(ubrn:String) = s"ENT$ubrn"
  private def generateErn = Random.nextInt(9999999).toString //to keep with same format as ubrn
  private def generateKey(id:String, suffix:String) = s"$period~$id~$suffix"



}