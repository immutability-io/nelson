package nelson

import cats.Monad
import cats.arrow.FunctionK
import cats.data.{Validated, ValidatedNel}
import cats.free.Free
import cats.effect.{Effect, IO, Timer}
import cats.syntax.functor._
import cats.syntax.monadError._

import fs2.{Pipe, Sink, Stream}

import java.util.concurrent.{ScheduledExecutorService, TimeoutException}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

object CatsHelpers {
  implicit val catsIOScalazInstances: scalaz.Monad[IO] with scalaz.Catchable[IO] =
    new scalaz.Monad[IO] with scalaz.Catchable[IO] {
      def bind[A, B](fa: IO[A])(f: A => IO[B]): IO[B] = fa.flatMap(f)
      def point[A](a: => A): IO[A] = IO(a)

      def attempt[A](fa: IO[A]): IO[scalaz.\/[Throwable, A]] = fa.attempt.map {
        case Left(a) => scalaz.-\/(a)
        case Right(b) => scalaz.\/-(b)
      }
      def fail[A](err: Throwable): IO[A] = IO.raiseError(err)
    }

  implicit def catsFreeScalazInstances[F[_]]: scalaz.Monad[Free[F, ?]] =
    new scalaz.Monad[Free[F, ?]] {
      def bind[A, B](fa: Free[F, A])(f: A => Free[F, B]): Free[F, B] = fa.flatMap(f)
      def point[A](a: => A): Free[F, A] = Free.pure(a)
    }

  implicit def scalazEitherCatsInstances[L]: Monad[scalaz.\/[L, ?]] =
    new Monad[scalaz.\/[L, ?]] {
      def flatMap[A, B](fa: scalaz.\/[L, A])(f: A => scalaz.\/[L, B]): scalaz.\/[L, B] =
        fa.flatMap(f)
      def pure[A](a: A): scalaz.\/[L, A] = scalaz.\/.right(a)

      @annotation.tailrec
      def tailRecM[A, B](a: A)(f: A => scalaz.\/[L, Either[A, B]]): scalaz.\/[L, B] =
        f(a) match {
          case left@scalaz.-\/(_) => left.asInstanceOf[scalaz.\/[L, B]] // yolo
          case scalaz.\/-(e) => e match {
            case Left(a) => tailRecM(a)(f)
            case right@Right(b) => scalaz.\/.right(b)
          }
        }
    }

  implicit class NelsonEnrichedEither[A, B](val either: Either[A, B]) extends AnyVal {
    def toDisjunction: scalaz.\/[A, B] = either match {
      case Left(a)  => scalaz.-\/(a)
      case Right(b) => scalaz.\/-(b)
    }
  }

  implicit class NelsonEnrichedDisjunction[A, B](val either: scalaz.\/[A, B]) extends AnyVal {
    def toValidatedNel: ValidatedNel[A, B] =
      either.fold(Validated.invalidNel, Validated.valid)

    def toValidated: Validated[A, B] =
      either.fold(Validated.invalid, Validated.valid)
  }

  implicit class NelsonEnrichedValidated[A, B](val validated: Validated[A, B]) extends AnyVal {
    def toDisjunction: scalaz.\/[A, B] =
      validated.fold(scalaz.\/.left, scalaz.\/.right)
  }

  implicit class NelsonEnrichedScalazFunctionK[F[_], G[_]](val functionK: scalaz.~>[F, G]) extends AnyVal {
    def asCats: FunctionK[F, G] = new FunctionK[F, G] {
      def apply[A](fa: F[A]): G[A] = functionK(fa)
    }
  }

  implicit class NelsonEnrichedCatsFunctionK[F[_], G[_]](val functionK: FunctionK[F, G]) extends AnyVal {
    def asScalaz: scalaz.~>[F, G] = new scalaz.~>[F, G] {
      def apply[A](fa: F[A]): G[A] = functionK(fa)
    }
  }

  implicit class NelsonEnrichedIO[A](val io: IO[A]) extends AnyVal {
    /** Run `other` if this IO fails */
    def or(other: IO[A]): IO[A] = io.attempt.flatMap {
      case Right(a) => IO.pure(a)
      case Left(e)  => other
    }

    /** Fail with error if the result of the IO does not satsify the predicate
      *
      * Taken from https://github.com/scalaz/scalaz/blob/series/7.3.x/concurrent/src/main/scala/scalaz/concurrent/Task.scala
      */
    def ensure(failure: => Throwable)(f: A => Boolean): IO[A] =
      io.flatMap(a => if (f(a)) IO.pure(a) else IO.raiseError(failure))

    def timed(timeout: FiniteDuration)(implicit ec: ExecutionContext, schedulerES: ScheduledExecutorService): IO[A] =
      IO.race(
        Timer[IO].sleep(timeout).as(new TimeoutException(s"Timed out after ${timeout.toMillis} milliseconds"): Throwable),
        io
      ).rethrow
  }

  private def sinkW[F[_], W, O](actualSink: Sink[F, W]): Sink[F, Either[W, O]] =
    stream => actualSink(stream.collect { case Left(e) => e })

  private def pipeO[F[_], W, O, O2](actualPipe: Pipe[F, O, O2]): Pipe[F, Either[W, O], Either[W, O2]] =
    _.flatMap {
      case Left(a)  => Stream.emit(Left(a))
      case Right(b) => actualPipe(Stream.emit(b)).map(Right(_))
    }

  implicit class NelsonEnrichedWriterStream[F[_], W, O](val stream: Stream[F, Either[W, O]]) {
    def observeW(sink: Sink[F, W])(implicit F: Effect[F], ec: ExecutionContext): Stream[F, Either[W, O]] =
      stream.observe(sinkW(sink))

    def stripW: Stream[F, O] = stream.collect { case Right(o) => o }

    def throughO[O2](pipe: Pipe[F, O, O2]): Stream[F, Either[W, O2]] =
      stream.through(pipeO(pipe))
  }
}
