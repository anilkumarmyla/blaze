package org.http4s.blaze.util

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import org.log4s.getLogger



/** Low resolution execution scheduler
  *
  * @note The ideas for [[org.http4s.blaze.util.TickWheelExecutor]] is based off of loosely came from the
  * Akka scheduler, which was based on the Netty HashedWheelTimer which was in term
  * based on concepts in <a href="http://cseweb.ucsd.edu/users/varghese/">George Varghese</a>
  * and Tony Lauck's paper <a href="http://cseweb.ucsd.edu/users/varghese/PAPERS/twheel.ps.Z">'Hashed
  * and Hierarchical Timing Wheels: data structures to efficiently implement a
  * timer facility'</a>
  *
  * @constructor primary constructor which immediately spins up a thread and begins ticking
  *
  * @param wheelSize number of spokes on the wheel. Each tick, the wheel will advance a spoke
  * @param tick duration between ticks
  */
class TickWheelExecutor(wheelSize: Int = 512, tick: Duration = 200.milli) {

  require(wheelSize > 0, "Need finite size number of ticks")
  require(tick.isFinite() && tick.toNanos != 0, "tick duration must be finite")

  // Types that form a linked list with links representing different events
  private sealed trait ScheduleEvent
  private case class Register(node: Node, next: ScheduleEvent) extends ScheduleEvent
  private case class Cancel(node: Node, next: ScheduleEvent) extends ScheduleEvent
  private case object Tail extends ScheduleEvent

  private[this] val logger = getLogger

  @volatile private var alive = true

  private val tickMilli = tick.toMillis
  private val _tickInv = 1.0/tickMilli.toDouble

  private val head = new AtomicReference[ScheduleEvent](Tail)

  private val clockFace: Array[Bucket] = {
    (0 until wheelSize).map(_ => new Bucket()).toArray
  }

  /////////////////////////////////////////////////////
  // new Thread that actually runs the execution.

  private val thread = new Thread(s"TickWheelExecutor: $wheelSize spokes, $tick interval") {
    override def run() {
      cycle(System.currentTimeMillis())
    }
  }

  thread.setDaemon(true)
  thread.start()

  /////////////////////////////////////////////////////

  def shutdown(): Unit = {
    alive = false
  }

  // Execute directly on this worker thread. ONLY for QUICK tasks...
  def schedule(r: Runnable, timeout: Duration): Cancellable = {
    schedule(r, Execution.directec, timeout)
  }

  def schedule(r: Runnable, ec: ExecutionContext, timeout: Duration): Cancellable = {
    if (!timeout.isFinite()) sys.error(s"Cannot submit infinite duration delays!")
    else if (alive) {
      val millis = timeout.toMillis
      if (millis > 0) {
        val expires = millis + System.currentTimeMillis()

        val node = new Node(r, ec, expires, null, null)

        def go(): Unit = {
          val h = head.get()
          if (!head.compareAndSet(h, Register(node, h))) go()
        }

        go()
        node
      }
      else {  // we can submit the task right now! Not sure why you would want to do this...
        try ec.execute(r)
        catch { case NonFatal(t) => onNonFatal(t) }
        Cancellable.finished
      }
    }
    else sys.error("TickWheelExecutor is shutdown")
  }

  // Deals with appending and removing tasks from the buckets
  private def handleTasks(): Unit = {

    @tailrec
    def go(task: ScheduleEvent): Unit = task match {
      case Cancel(n, nxt) =>
        n.canceled = true
        n.unlink()
        go(nxt)

      case Register(n, nxt) =>
        if (!n.canceled) getBucket(n.expiration).add(n)
        go(nxt)

      case Tail => // NOOP
    }

    val tasks = head.getAndSet(Tail)
    go(tasks)
  }

  @tailrec
  private def cycle(lastTickTime: Long): Unit = {

    handleTasks()  // Deal with scheduling and cancellations

    val now = System.currentTimeMillis()
    val lastTick = (lastTickTime * _tickInv).toLong
    val thisTick = (now * _tickInv).toLong
    val ticks = math.min(thisTick - lastTick, wheelSize)

    @tailrec
    def go(i: Long): Unit = if (i < ticks) { // will do at least one tick
      val ii = ((lastTick + i) % wheelSize).toInt
      clockFace(ii).prune(now) // Remove canceled and submit expired tasks from the current spoke
      go(i + 1)
    }
    go(0)

    if (alive) {
      // Make up for execution time, unlikely to be significant
      val left = tickMilli - (System.currentTimeMillis() - now)
      if (left > 0) Thread.sleep(left)
      cycle(now)
    }
    else {  // delete all our buckets so we don't hold any references
      for { i <- 0 until wheelSize } clockFace(i) = null
    }
  }

  protected def onNonFatal(t: Throwable) {
    logger.error(t)("Non-Fatal Exception caught while executing scheduled task")
  }
  
  private def getBucket(expiration: Long): Bucket = {
    val i = ((expiration*_tickInv).toLong) % wheelSize
    clockFace(i.toInt)
  }

  private class Bucket {
    // An empty cell serves as the head of the linked list
    private val head: Node = new Node(null, null, -1, null, null)

    /** Removes expired and canceled elements from this bucket, executing expired elements
      *
      * @param time current system time (in milliseconds)
      */
    def prune(time: Long) {
      @tailrec
      def checkNext(prev: Node): Unit = {
        val next = prev.next
        if (next ne null) {
          if (next.canceled) {   // remove it
            logger.error("Tickwheel has canceled node in bucket: shouldn't get here.")
            next.unlink()
          }
          else if (next.expiresBy(time)) {
            next.run()
            next.unlink()
            checkNext(prev)
          }
          else checkNext(next)  // still valid
        }
      }

      checkNext(head)
    }
    
    def add(node: Node): Unit =  {
      node.insertAfter(head)
    }
  }

  /** A Link in a single linked list which can also be passed to the user as a Cancellable
    * @param r [[java.lang.Runnable]] which will be executed after the expired time
    * @param ec [[scala.concurrent.ExecutionContext]] on which to execute the Runnable
    * @param expiration time in milliseconds after which this Node is expired
    * @param next next Node in the list or `tailNode` if this is the last element
    */
  final private class Node(r: Runnable,
                          ec: ExecutionContext,
              val expiration: Long,
                    var prev: Node,
                    var next: Node,
                var canceled: Boolean = false) extends Cancellable {

    /** Remove this node from its linked list */
    def unlink(): Unit = {
      if (prev != null) { // Every node that is in a bucket should have a `prev`
        prev.next = next
      }

      if (next != null) {
        next.prev = prev
      }

      prev = null
      next = null
    }

    /** Insert this node immediately after `node` */
    def insertAfter(node: Node): Unit = {
      val n = node.next
      node.next = this

      if (n != null) {
        n.prev = this
      }

      this.prev = node
      this.next = n
    }

    def expiresBy(now: Long): Boolean = now >= expiration

    /** Schedule a the TickWheel to remove this node next tick */
    def cancel(): Unit = {
      def go(): Unit = {
        val h = head.get()
        if (!head.compareAndSet(h, Cancel(this, h))) go()
      }

      go()
    }

    def run() = try ec.execute(r) catch { case NonFatal(t) => onNonFatal(t) }
  }
}

trait Cancellable {
  def cancel(): Unit
}

object Cancellable {
  val finished = new Cancellable {
    def cancel(): Unit = {}
  }
}
