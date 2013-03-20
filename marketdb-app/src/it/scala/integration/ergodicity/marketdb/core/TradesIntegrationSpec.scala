package integration.ergodicity.marketdb.core

import collection.JavaConversions
import com.ergodicity.marketdb.MarketDbApp
import com.ergodicity.marketdb.iteratee.MarketDbReader
import com.ergodicity.marketdb.model.Market
import com.ergodicity.marketdb.model.Security
import com.ergodicity.marketdb.model.TradePayload
import com.ergodicity.marketdb.model._
import com.twitter.ostrich.admin.RuntimeEnvironment
import com.twitter.util.Future
import integration.ergodicity.marketdb.TimeRecording
import java.io.File
import org.joda.time.DateTime
import org.mockito.Mockito
import org.scala_tools.time.Implicits._
import org.scalatest.{WordSpec, GivenWhenThen}
import org.slf4j.LoggerFactory
import scala.Predef._

class TradesIntegrationSpec extends WordSpec with GivenWhenThen with TimeRecording {
  val log = LoggerFactory.getLogger(classOf[OrdersIntegrationSpec])

  val NoSystem = true

  val market = Market("RTS")
  val security = Security("RTS 3.12")
  val time = new DateTime

  "MarketDb" must {

    val runtime = RuntimeEnvironment(this, Array[String]())
    runtime.configFile = new File("./config/it.scala")
    val marketDBApp = runtime.loadRuntimeConfig[MarketDbApp]()

    implicit val reader = Mockito.mock(classOf[MarketDbReader])
    Mockito.when(reader.client).thenReturn(marketDBApp.marketDb.client)

    "persist new trade" in {
      val payload = TradePayload(market, security, 11l, BigDecimal("111"), 1, time, NoSystem)

      // Execute
      val futureReaction = recordTime("Add trade", () => marketDBApp.marketDb.addTrade(payload))
      val reaction = recordTime("Reaction", () => futureReaction.apply())

      log.info("Trade reaction: " + reaction)
    }

    "persist new trades and scan them later" in {
      val time1 = new DateTime(1970, 01, 01, 1, 0, 0, 0)
      val time2 = new DateTime(1970, 01, 01, 1, 0, 1, 0)

      val payload1 = TradePayload(market, security, 111l, BigDecimal("111"), 1, time1, NoSystem)
      val payload2 = TradePayload(market, security, 112l, BigDecimal("112"), 1, time2, NoSystem)

      val f1 = marketDBApp.marketDb.addTrade(payload1)
      val f2 = marketDBApp.marketDb.addTrade(payload2)

      // Wait for trades persisted
      Future.join(List(f1, f2))()

      // -- Verify two rows for 1970 Jan 1
      val interval = new DateTime(1970, 01, 01, 0, 0, 0, 0) to new DateTime(1970, 01, 01, 23, 0, 0, 0)
      val timeSeries = marketDBApp.marketDb.trades(market, security, interval).apply()

      val scanner = {
        val scanner = marketDBApp.marketDb.client.newScanner(timeSeries.qualifier.table)
        scanner.setStartKey(timeSeries.qualifier.startKey)
        scanner.setStopKey(timeSeries.qualifier.stopKey)
        scanner
      }

      val rows = scanner.nextRows().joinUninterruptibly()
      log.info("ROWS Jan 1: " + rows)

      import TradeProtocol._
      import sbinary.Operations._
      val trades = for (list <- JavaConversions.asScalaIterator(rows.iterator());
                        kv <- JavaConversions.asScalaIterator(list.iterator())) yield fromByteArray[TradePayload](kv.value())

      trades foreach {
        trade => log.info("Trade: " + trade)
      }

      assert(rows.size() == 1)
      assert(rows.get(0).size() == 2)

      assert(scanner.nextRows().joinUninterruptibly() == null)
    }

    "return null if no trades exists" in {
      // -- Verify two rows for 1970 Feb 1
      val interval = new DateTime(1970, 02, 01, 0, 0, 0, 0) to new DateTime(1970, 02, 01, 23, 0, 0, 0)
      val timeSeries = marketDBApp.marketDb.trades(market, security, interval).apply()

      val scanner = {
        val scanner = marketDBApp.marketDb.client.newScanner(timeSeries.qualifier.table)
        scanner.setStartKey(timeSeries.qualifier.startKey)
        scanner.setStopKey(timeSeries.qualifier.stopKey)
        scanner
      }

      val rows = scanner.nextRows().joinUninterruptibly()
      log.info("ROWS Feb1 1: " + rows)

      assert(rows == null)
    }
  }
}
