package assembler

import global.AppParams
import global.Configs.conf
import service._

import scala.reflect.io.File

object AssemblerMain extends AddNewPeriodDataService {

  def main(args: Array[String]) {

    conf.set("hbase.zookeeper.quorum", args(21))
    conf.set("hbase.zookeeper.property.clientPort", args(22))
    conf.setInt("hbase.mapreduce.bulkload.max.hfiles.perRegion.perFamily", 500)
    val params = args.take(21) ++ args.takeRight(8)

    implicit val appParams: AppParams = AppParams(params)

    try {
      appParams.ACTION match {

        case "add-calculated-period" => loadNewPeriodWithCalculationsData
        case "data-integrity-report" => {
          conf.setInt("spark.sql.broadcastTimeout", 2400)
          //printReport(appParams)
        }
        case arg => throw new IllegalArgumentException(s"action not recognised: $arg")

      }
    } finally {

      if (appParams.ENV == "local") {
        val entHFile = File(appParams.PATH_TO_ENTERPRISE_HFILE)
        entHFile.deleteRecursively()
        val linksHFile = File(appParams.PATH_TO_LINKS_HFILE)
        linksHFile.deleteRecursively()
        val leuHFile = File(appParams.PATH_TO_LEGALUNITS_HFILE)
        leuHFile.deleteRecursively()
        val louHFile = File(appParams.PATH_TO_LOCALUNITS_HFILE)
        louHFile.deleteRecursively()

        println("HFiles deleted")

      }

    }

  }

}
