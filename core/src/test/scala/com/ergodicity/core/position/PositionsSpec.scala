package com.ergodicity.core.position

import akka.pattern._
import akka.event.Logging
import com.ergodicity.core.IsinId
import akka.actor.{ActorRef, ActorSystem}
import java.util.concurrent.TimeUnit
import akka.dispatch.Await
import akka.testkit.{TestFSMRef, ImplicitSender, TestKit}
import com.ergodicity.core.AkkaConfigurations
import java.nio.ByteBuffer
import com.ergodicity.cgate.scheme.Pos
import java.math.BigDecimal
import com.ergodicity.cgate.{DataStreamState, DataStream}
import com.ergodicity.cgate.repository.Repository.Snapshot
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack, CurrentState}
import org.scalatest.{BeforeAndAfter, GivenWhenThen, BeforeAndAfterAll, WordSpec}
import com.ergodicity.core.position.Positions.{OpenPositions, GetOpenPositions, GetPosition}

class PositionsSpec extends TestKit(ActorSystem("PositionsSpec", AkkaConfigurations.ConfigWithDetailedLogging)) with ImplicitSender with WordSpec with GivenWhenThen with BeforeAndAfterAll with BeforeAndAfter {
  val log = Logging(system, self)

  implicit val TimeOut = akka.util.Timeout(100, TimeUnit.MILLISECONDS)

  override def afterAll() {
    system.shutdown()
  }

  after {
    Thread.sleep(100)
  }

  val isin = 101
  val position = {
    val buff = ByteBuffer.allocate(1000)
    val pos = new Pos.position(buff)
    pos.set_isin_id(isin)
    pos.set_buys_qty(1)
    pos.set_pos(1)
    pos.set_net_volume_rur(new BigDecimal(100))
    pos
  }

  val updatedPosition = {
    val buff = ByteBuffer.allocate(1000)
    val pos = new Pos.position(buff)
    pos.set_isin_id(isin)
    pos.set_buys_qty(2)
    pos.set_pos(2)
    pos.set_net_volume_rur(new BigDecimal(200))
    pos
  }

  "Positions" must {

    import PositionState._
    import PositionsState._

    "initialized in Binded state" in {
      val positions = TestFSMRef(new Positions(TestFSMRef(new DataStream, "DataStream")), "Positions")
      assert(positions.stateName == Binded)
    }

    "bind to stream and go to LoadingPositions later" in {
      val PosDS = TestFSMRef(new DataStream, "DataStream")
      val positions = TestFSMRef(new Positions(PosDS), "Positions")

      when("PosRepl data Streams goes online")
      positions ! Transition(PosDS, DataStreamState.Opened, DataStreamState.Online)

      then("should go to LoadingPositions state")
      assert(positions.stateName == LoadingPositions)
    }
    
    "go to online state after first snapshot received" in {
      val PosDS = TestFSMRef(new DataStream, "DataStream")
      val positions = TestFSMRef(new Positions(PosDS), "Positions")
      val underlying = positions.underlyingActor.asInstanceOf[Positions]

      when("PosRepl data Streams goes online")
      positions ! Transition(PosDS, DataStreamState.Opened, DataStreamState.Online)

      then("should go to LoadingPositions state")
      assert(positions.stateName == LoadingPositions)

      when("first snapshot received")
      positions ! Snapshot(underlying.PositionsRepository, position :: Nil)

      then("should go to Online state")
      assert(positions.stateName == Online)
      
      and("hande snapshot values")
      assert(positions.stateData.positions.size == 1)
    }

    "handle first repository snapshot" in {
      val positions = TestFSMRef(new Positions(TestFSMRef(new DataStream, "DataStream")), "Positions")
      val underlying = positions.underlyingActor.asInstanceOf[Positions]

      positions.setState(Online, TrackingPositions())

      val snapshot = Snapshot(underlying.PositionsRepository, position :: Nil)
      positions ! snapshot

      assert(positions.stateData.positions.size == 1)

      val positionRef = positions.stateData.positions(IsinId(isin))
      positionRef ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(positionRef, OpenedPosition))
    }

    "terminate outdated positions" in {
      val positions = TestFSMRef(new Positions(TestFSMRef(new DataStream, "DataStream")), "Positions")
      val underlying = positions.underlyingActor.asInstanceOf[Positions]

      positions.setState(Online, TrackingPositions())

      positions ! Snapshot(underlying.PositionsRepository, position :: Nil)

      assert(positions.stateData.positions.size == 1)

      val positionRef = positions.stateData.positions(IsinId(isin))
      positionRef ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(positionRef, OpenedPosition))

      positions ! Snapshot(underlying.PositionsRepository, List[Pos.position]())

      assert(positions.stateData.positions.size == 1)
      expectMsg(Transition(positionRef, OpenedPosition, UndefinedPosition))
    }

    "update existing positions" in {
      val positions = TestFSMRef(new Positions(TestFSMRef(new DataStream, "DataStream")), "Positions")

      val underlying = positions.underlyingActor.asInstanceOf[Positions]

      positions.setState(Online, TrackingPositions())
      positions ! Snapshot(underlying.PositionsRepository, position :: Nil)

      assert(positions.stateData.positions.size == 1)

      val positionRef = positions.stateData.positions(IsinId(isin))
      positionRef ! SubscribePositionUpdates(self)

      positions ! Snapshot(underlying.PositionsRepository, updatedPosition :: Nil)
      expectMsg(PositionUpdated(positionRef, PositionData(0, 2, 0, 2, new BigDecimal(200), 0)))
    }

    "create new positions if it doesn't exists" in {
      val positions = TestFSMRef(new Positions(TestFSMRef(new DataStream, "DataStream")), "Positions")
      positions.setState(Online, TrackingPositions())

      val position = Await.result((positions ? GetPosition(IsinId(isin))).mapTo[ActorRef], TimeOut.duration)

      assert(positions.stateData.positions.size == 1)

      position ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(position, UndefinedPosition))
    }

    "return existing position on track position event" in {
      val positions = TestFSMRef(new Positions(TestFSMRef(new DataStream, "DataStream")), "Positions")
      positions.setState(Online, TrackingPositions())

      val underlying = positions.underlyingActor.asInstanceOf[Positions]

      positions ! Snapshot(underlying.PositionsRepository, position :: Nil)

      val positionRef = Await.result((positions ? GetPosition(IsinId(isin))).mapTo[ActorRef], TimeOut.duration)
      positionRef ! SubscribeTransitionCallBack(self)
      expectMsg(CurrentState(positionRef, OpenedPosition))
    }

    "get all opened positions" in {
      val positions = TestFSMRef(new Positions(TestFSMRef(new DataStream, "DataStream")), "Positions")
      positions.setState(Online, TrackingPositions())

      val underlying = positions.underlyingActor.asInstanceOf[Positions]

      positions ! Snapshot(underlying.PositionsRepository, position :: Nil)

      val openPositions = Await.result((positions ? GetOpenPositions).mapTo[OpenPositions], TimeOut.duration)
      assert(openPositions.positions.size == 1)
      assert(openPositions.positions.iterator.next() == IsinId(isin))
    }
  }

}