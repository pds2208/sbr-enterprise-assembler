package test.utils

import global.AppParams
import model.domain._
import model.hfile.HFileCell
import org.apache.spark.rdd.RDD

import scala.util.Try
/**
  *
  */
trait HFileTestUtils {




  def entToHFileCells(ents:RDD[HFileRow])(implicit configs: AppParams) = ents.flatMap(row =>
              row.cells.map(cell => HFileCell(row.key,configs.HBASE_ENTERPRISE_COLUMN_FAMILY,cell.column,cell.value)))


  def linksToHFileCells(ents:RDD[HFileRow])(implicit configs: AppParams) = ents.flatMap(row =>
              row.cells.map(cell => HFileCell(row.key,configs.HBASE_LINKS_COLUMN_FAMILY,cell.column,cell.value)))


  def localUnitsToHFileCells(ents:RDD[HFileRow])(implicit configs: AppParams) = ents.flatMap(row =>
              row.cells.map(cell => HFileCell(row.key,configs.HBASE_LOCALUNITS_COLUMN_FAMILY,cell.column,cell.value)))




   def assignStaticLinkIds(rows:Seq[HFileRow]): Set[HFileRow] = {


    //dictionary mapping actual erns to static
    val ernsDictionary: Seq[(String, String)] = {

      val erns: Seq[(String, Int)] = rows.collect{case row if(row.cells.find(_.column=="p_ENT").isDefined) => {row.cells.collect{case KVCell("p_ENT",value) => value}}}.flatten.zipWithIndex

      erns.map(ernTup => {
        val (ern,index) = ernTup
        (ern,"testEnterpriseId-"+({index+1}.toString*5))

        })}



    val lurnsDictionary: Seq[(String, String)] = {
      val lurns: Seq[(String, Int)] = rows.collect{case row if(row.cells.find(_.value=="LOU").isDefined) => {row.cells.collect{case KVCell(lurn,"LOU") => lurn}}}.flatten.zipWithIndex

     lurns.map(lurnTup => {
        val (lurn,index) = lurnTup
        (lurn.replace("c_",""),"testLocalUnitId-"+({index+1}.toString*5))

        })}


     def replaceInKey(key:String) = {
       val entityType = key.split("~")(1)

       val id = key.split(s"~$entityType~").head
       val replacementOpt  = entityType match{
         case "ENT" => ernsDictionary.find(_._1 == id).map(_._2)
         case "LOU" => lurnsDictionary.find(_._1 == id).map(_._2)
         case _ => None
       }
       replacementOpt.map(replacement => key.replace(id,replacement))
     }

     def getId(key:String) = {
         val entityTypes = Seq("ENT","LOU")
         Try{entityTypes.collect{case entityType if(key.contains(s"~$entityType~")) => key.split(s"~$entityType~").head
         }.head}.toOption

     }

     def setParentErns(cells:Iterable[KVCell[String,String]]) = cells.map(replaceParentErn)


     def replaceParentErn(cell:KVCell[String,String]) =  if (cell.column == "p_ENT") {
       val value = ernsDictionary.find(_._1 == cell.value)
       cell.copy(value = value.get._2)
     } else cell


     //replace erns in rows:
     rows.map { case row => {
       val staticKey = getId(row.key)//.flatMap(id => replaceInKey(id))
       if (staticKey.isDefined) {
         val updatedCells = row.cells.map{case cell@KVCell(col,value) => {
           val staticLurn = lurnsDictionary.find(_._1==col.replace("c_",""))
           if(value == "LOU" && staticLurn.isDefined) KVCell(staticLurn.get._2,value)
           else if(col=="p_ENT") replaceParentErn(cell)
           else cell
         }
         }
         replaceInKey(row.key).map(key => HFileRow(key, updatedCells)).getOrElse(HFileRow(staticKey.get,updatedCells))

       } else if (row.cells.find(cell => cell.column == "p_ENT").isDefined) {
         row.copy(row.key, setParentErns(row.cells))
       }  else row

     }
     }.toSet



   }


   def assignStaticLousLurns(rows:Seq[HFileRow]): Set[HFileRow] = {
     //dictionary mapping actual erns to static
     val ernsDictionary: Seq[(String, String)] = {

       val erns: Seq[(String, Int)] = rows.collect{case row if(row.cells.find(_.column=="ern").isDefined) => {row.cells.find(_.column=="ern").get.value}}.zipWithIndex

       erns.map(ernTup => {
         val (ern,index) = ernTup
         (ern,"testEnterpriseId-"+({index+1}.toString*5))

       })}



     val lurnsDictionary: Seq[(String, String)] = {
       val lurns: Seq[(String, Int)] = rows.collect{case row if(row.cells.find(_.column=="lurn").isDefined) => {row.cells.collect{case KVCell("lurn",value) => value}}}.flatten.zipWithIndex

       lurns.map(lurnTup => {
         val (lurn,index) = lurnTup
         (lurn,"testLocalUnitId-"+({index+1}.toString*5))

       })}


     rows.map(row => {
       val ids = row.key.split("~")
       val keyErnReversed = ids(0)
       val keyErn: String = keyErnReversed.reverse
       val keyLurn: String = ids(2)
       val staticErnKey = ernsDictionary.find(_._1==keyErn).map(_._2)
       val staticLurnKey = lurnsDictionary.find(_._1==keyLurn).map(_._2)
       val staticErnReversedKey = staticErnKey.map(_.reverse)
       val rowKey = staticErnReversedKey.flatMap(ern => staticLurnKey.map(lurn => s"$ern~201802~$lurn")).getOrElse(row.key)
       val cells: Iterable[KVCell[String, String]] = row.cells.flatMap{case cell@KVCell(col, value) => {
         staticErnKey.flatMap(ern => staticLurnKey.map(lurn => {
           if(col=="ern") KVCell(col,ern)
           else if(col=="lurn") KVCell(col,lurn)
           else cell
       }))


       }}

       HFileRow(rowKey,cells)

     }).toSet

   }


  def assignStaticIds(lous:Seq[LocalUnit]) = {

    val idsDictionary: Seq[(String, (String, String))] = {

      val ids: Seq[((String, String),Int)] = lous.map(lou => (lou.lurn,lou.ern)).zipWithIndex

      ids.map(idTup => {
        val ((lurn, ern),index) = idTup
        (lurn,("testLocalUnitId-"+({index+1}.toString*5),"testEnterpriseId-"+({index+1}.toString*5)))

      })}

    lous.map(lou => {

      val (lurn,ern) = idsDictionary.find(_._1==lou.lurn).get._2
      lou.copy(lurn=lurn,ern=ern)

    })

  }


}