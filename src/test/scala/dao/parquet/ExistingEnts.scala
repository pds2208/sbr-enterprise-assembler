package dao.parquet

import model.domain.{HFileRow, KVCell}

trait ExistingEnts {
  val ents = Seq(
  HFileRow("1100000002~201803",List(KVCell("address1","P O BOX 22"), KVCell("address2","INDUSTRIES HOUSE"), KVCell("address3","WHITE LANE"), KVCell("address4","REDDITCH"), KVCell("address5","WORCESTERSHIRE"), KVCell("entref","9900000009"), KVCell("ern","2000000011"), KVCell("legal_status","1"), KVCell("name","INDUSTRIES LTD"), KVCell("paye_empees","2"), KVCell("paye_jobs","4"), KVCell("postcode","B22 2TL"), KVCell("sic07","12345"), KVCell("trading_style","A"))),
  HFileRow("1100000003~201803",List(KVCell("address1","GOGGESHALL ROAD"), KVCell("address2","EARLS COLNE"), KVCell("address3","COLCHESTER"), KVCell("entref","9900000126"), KVCell("ern","3000000011"), KVCell("legal_status","1"), KVCell("name","BLACKWELLGROUP LTD"), KVCell("paye_empees","4"), KVCell("paye_jobs","4"), KVCell("postcode","CO6 2JX"), KVCell("sic07","23456"), KVCell("trading_style","B"))),
  HFileRow("1100000004~201803",List(KVCell("address1","BSTER DEPT"), KVCell("address2","MAILPOINT A1F"), KVCell("address3","P O BOX 41"), KVCell("address4","NORTH HARBOUR"), KVCell("address5","PORTSMOUTH"), KVCell("entref","9900000242"), KVCell("ern","4000000011"), KVCell("legal_status","1"), KVCell("name","IBM LTD"), KVCell("paye_empees","5"), KVCell("paye_jobs","5"), KVCell("postcode","PO6 3AU"), KVCell("sic07","34567"), KVCell("trading_style","C")))
  )
}