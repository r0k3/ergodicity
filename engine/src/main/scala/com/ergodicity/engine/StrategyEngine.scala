package com.ergodicity.engine

import akka.actor.{Actor, ActorRef}
import strategy.Strategy

case class RegisterStrategy(strategy: Strategy, manager: ActorRef)

class StrategyEngine extends Actor {
  protected def receive = null
}