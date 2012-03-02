package com.ergodicity.marketdb.loader.util

import scalaz._
import IterV._
import scalaz.{Input, IterV}
import com.ergodicity.marketdb.model.TradePayload
import org.slf4j.LoggerFactory
import org.jboss.netty.buffer.ChannelBuffers
import com.twitter.finagle.kestrel.Client
import com.twitter.ostrich.stats.Stats
import com.twitter.concurrent.Offer
import java.util.concurrent.atomic.AtomicReference

case class BulkLoaderReport[E](count: Int, list: List[E])

case class BulkLoaderSetting(size: Int, limit: Option[Int])

object Iteratees {
  private[this] val log = LoggerFactory.getLogger("Iteratees")
  
  def printer[E](log: org.slf4j.Logger): IterV[E, Boolean] = {
    def step(is: Boolean, e: E)(s: Input[E]): IterV[E, Boolean] = {
      log.info("STEP: " + e)
      s(el = e2 => Cont(step(is, e2)),
        empty = Cont(step(is, e)),
        eof = Done(is, EOF[E]))
    }

    def first(s: Input[E]): IterV[E, Boolean] = {
      s(el = e1 => Cont(step(true, e1)),
        empty = Cont(first),
        eof = Done(true, EOF[E]))
    }

    Cont(first)
  }

  def kestrelBulkLoader[E](queue: String, client: Client)
                          (implicit serializer: List[E] => Array[Byte], settings: BulkLoaderSetting): IterV[E, BulkLoaderReport[E]] = {

    def flush(list: List[E]) {
      val bytes = serializer(list)
      client.write(queue, OfferOnce(ChannelBuffers.wrappedBuffer(bytes)))      
    }

    def flushIfRequired(e: E, rep: BulkLoaderReport[E]) = {
      if (rep.list.size >= settings.size) {
        log.info("Flush data to channel; Position: " + rep.count + "; Size: " + rep.list.size)
        flush(rep.list)
        BulkLoaderReport(rep.count+1, e :: Nil)
      } else {
        BulkLoaderReport(rep.count+1, e :: rep.list)
      }
    }

    def flushRemaining(rep: BulkLoaderReport[E]) = {
      log.info("Flush remaining; Position: " + rep.count + "; Size: " + rep.list.size)
      flush(rep.list)
      BulkLoaderReport[E](rep.count, Nil)
    }

    def step(is: BulkLoaderReport[E], e: E)(s: Input[E]): IterV[E, BulkLoaderReport[E]] = {
      s(el = e2 => {
        Stats.incr("trades_processed", 1);
        if (settings.limit.isDefined && is.count >= settings.limit.get) {
          val afterFlush = flushRemaining(is)
          Done(afterFlush, EOF[E])
        } else Cont(step(flushIfRequired(e2, is), e2))
      },
        empty = Cont(step(is, e)),
        eof = Done(flushRemaining(is), EOF[E]))
    }

    def first(s: Input[E]): IterV[E, BulkLoaderReport[E]] = {
      s(el = e1 => {
        Cont(step(BulkLoaderReport(1, List(e1)), e1))
      },
        empty = Cont(first),
        eof = Done(BulkLoaderReport(0, Nil), EOF[E]))
    }

    Cont(first)
  }

  def counter[E]: IterV[E, Int] = {
    def step(is: Int, e: E)(s: Input[E]): IterV[E, Int] = {
      s(el = e2 => Cont(step(is + 1, e2)),
        empty = Cont(step(is, e)),
        eof = Done(is, EOF[E]))
    }

    def first(s: Input[E]): IterV[E, Int] = {
      s(el = e1 => Cont(step(1, e1)),
        empty = Cont(first),
        eof = Done(0, EOF[E]))
    }

    Cont(first)
  }
}

object OfferOnce {
  def apply[A](value: A): Offer[A] = new Offer[A] {
    val ref = new AtomicReference[Option[A]](Some(value))

    def objects = Seq()

    def poll() = ref.getAndSet(None).map(() => _)

    def enqueue(setter: this.type#Setter) = null
  }
  
}