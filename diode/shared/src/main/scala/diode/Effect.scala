package diode

import diode.util.RunAfter

import scala.concurrent._
import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

trait Effect {
  /**
    * Runs the effect and dispatches the result of the effect.
    *
    * @param dispatch Function to dispatch the effect result with.
    * @return A future that completes when the effect completes.
    */
  def run(dispatch: AnyRef => Unit): Future[Unit]

  /**
    * Combines two effects so that will be run in parallel.
    */
  def +(that: Effect): EffectSet

  /**
    * Combines another effect with this one, to be run after this effect.
    */
  def >>(that: Effect): EffectSeq

  /**
    * Combines another effect with this one, to be run before this effect.
    */
  def <<(that: Effect): EffectSeq

  /**
    * Returns the number of effects
    */
  def size: Int

  /**
    * Runs the effect function and returns its value (a Future[AnyRef])
    */
  def toFuture: Future[AnyRef]

  /**
    * Delays the execution of this effect by duration `delay`
    */
  def after(delay: FiniteDuration)(implicit runner: RunAfter): Effect

  /**
    * Creates a new effect by applying a function to the successful result of
    * this effect. If this effect is completed with an exception then the new
    * effect will also contain this exception.
    */
  def map(g: AnyRef => AnyRef): Effect

  /**
    * Creates a new effect by applying a function to the successful result of
    * this effect, and returns the result of the function as the new future.
    * If this effect is completed with an exception then the new
    * effect will also contain this exception.
    */
  def flatMap(g: AnyRef => Future[AnyRef]): Effect

  def ec: ExecutionContext
}

abstract class EffectBase(val ec: ExecutionContext) extends Effect {
  self =>
  override def +(that: Effect) = new EffectSet(this, Set(that), ec)

  override def >>(that: Effect) = new EffectSeq(this, List(that), ec)

  override def <<(that: Effect) = new EffectSeq(that, List(this), ec)

  override def size = 1

  override def after(delay: FiniteDuration)(implicit runner: RunAfter): Effect = new EffectBase(ec) {
    private def executeWith[A](f: Effect => Future[A]): Future[A] =
      runner.runAfter(delay)(()).flatMap(_ => f(self))(ec)

    override def run(dispatch: (AnyRef) => Unit) =
      executeWith(_.run(dispatch))

    override def toFuture =
      executeWith(_.toFuture)
  }

  override def map(g: AnyRef => AnyRef): Effect =
    new EffectSingle(() => toFuture.map(g)(ec), ec)

  override def flatMap(g: AnyRef => Future[AnyRef]): Effect =
    new EffectSingle(() => toFuture.flatMap(g)(ec), ec)
}

/**
  * Wraps a function to be executed later. Function must return a `Future[A]` and the returned
  * action is automatically dispatched when `run` is called.
  *
  * @param f The effect function, returning a `Future[A]`
  */
class EffectSingle[A <: AnyRef](f: () => Future[A], override implicit val ec: ExecutionContext) extends EffectBase(ec) {
  override def run(dispatch: AnyRef => Unit) = f().map(dispatch)

  override def toFuture = f()
}

/**
  * Wraps multiple `Effects` to be executed later. Effects are executed in the order they appear and the
  * next effect is run only after the previous has completed. If an effect fails, the execution stops.
  *
  * @param head First effect to be run.
  * @param tail Rest of the effects.
  */
class EffectSeq(head: Effect, tail: Seq[Effect], override implicit val ec: ExecutionContext) extends EffectBase(ec) {
  private def executeWith[A](f: Effect => Future[A]): Future[A] =
    tail.foldLeft(f(head)) { (prev, effect) => prev.flatMap(_ => f(effect)) }

  override def run(dispatch: (AnyRef) => Unit) =
    executeWith(_.run(dispatch))

  override def >>(that: Effect) =
    new EffectSeq(head, tail :+ that, ec)

  override def <<(that: Effect) =
    new EffectSeq(that, head +: tail, ec)

  override def size =
    1 + tail.map(_.size).sum

  override def toFuture =
    executeWith(_.toFuture)

  override def map(g: AnyRef => AnyRef) =
    new EffectSeq(head.map(g), tail.map(_.map(g)), ec)

  override def flatMap(g: AnyRef => Future[AnyRef]) =
    new EffectSeq(head.flatMap(g), tail.map(_.flatMap(g)), ec)
}

/**
  * Wraps multiple `Effects` to be executed later. Effects are executed in parallel without any ordering.
  *
  * @param head First effect to be run.
  * @param tail Rest of the effects.
  */
class EffectSet(head: Effect, tail: Set[Effect], override implicit val ec: ExecutionContext) extends EffectBase(ec) {
  private def executeWith[A](f: Effect => Future[A]): Future[Set[A]] =
    Future.traverse(tail + head)(f(_))

  override def run(dispatch: (AnyRef) => Unit) =
    executeWith(_.run(dispatch)).asInstanceOf[Future[Unit]]

  override def +(that: Effect) =
    new EffectSet(head, tail + that, ec)

  override def size =
    1 + tail.map(_.size).sum

  override def toFuture =
    executeWith(_.toFuture)

  override def map(g: AnyRef => AnyRef) =
    new EffectSet(head.map(g), tail.map(_.map(g)), ec)

  override def flatMap(g: AnyRef => Future[AnyRef]) =
    new EffectSet(head.flatMap(g), tail.map(_.flatMap(g)), ec)
}

object Effect {
  type EffectF[A] = () => Future[A]

  def apply[A <: AnyRef](f: => Future[A])(implicit ec: ExecutionContext): EffectSingle[A] =
    new EffectSingle(f _, ec)

  def apply(f: => Future[AnyRef], tail: EffectF[AnyRef]*)(implicit ec: ExecutionContext): EffectSet =
    new EffectSet(new EffectSingle(f _, ec), tail.map(f => new EffectSingle(f, ec)).toSet, ec)

  /**
    * Converts a lazy action value into an effect. Typically used in combination with other effects or
    * with `after` to delay execution.
    */
  def action[A <: AnyRef](action: => A)(implicit ec: ExecutionContext): EffectSingle[A] =
    new EffectSingle(() => Future.successful(action), ec)

  implicit def f2effect[A <: AnyRef](f: EffectF[A])(implicit ec: ExecutionContext): EffectSingle[A] = new EffectSingle(f, ec)
}
