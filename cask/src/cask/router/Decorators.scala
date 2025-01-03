package cask.router

import cask.internal.Conversion
import cask.model.{Request, Response}

/**
  * A [[Decorator]] allows you to annotate a function to wrap it, via
  * `wrapFunction`. You can use this to perform additional validation before or
  * after the function runs, provide an additional parameter list of params,
  * open/commit/rollback database transactions before/after the function runs,
  * or even retrying the wrapped function if it fails.
  *
  * Calls to the wrapped function are done on the `delegate` parameter passed
  * to `wrapFunction`, which takes a `Map` representing any additional argument
  * lists (if any).
  */
trait Decorator[OuterReturned, InnerReturned, Input, InputContext] extends scala.annotation.Annotation {
  final type InputTypeAlias = Input
  type InputParser[T] <: ArgReader[Input, T, InputContext]
  final type Delegate = (InputContext, Map[String, Input]) => Result[InnerReturned]
  def wrapFunction(ctx: Request, delegate: Delegate): Result[OuterReturned]
  def getParamParser[T](implicit p: InputParser[T]) = p
}
object Decorator{
  /**
   * A stack of [[Decorator]]s is invoked recursively: each decorator's `wrapFunction`
   * is invoked around the invocation of all inner decorators, with the inner-most
   * decorator finally invoking the route's [[EntryPoint.invoke]] function.
   *
   * Each decorator (and the final `Endpoint`) contributes a dictionary of name-value
   * bindings, which are eventually all passed to [[EntryPoint.invoke]]. Each decorator's
   * dictionary corresponds to a different argument list on [[EntryPoint.invoke]]. The
   * bindings passed from the router are aggregated with those from the `EndPoint` and
   * used as the first argument list.
   */
  def invoke[T](ctx: Request,
                endpoint: Endpoint[_, _, _, _],
                entryPoint: EntryPoint[T, _],
                routes: T,
                remainingDecorators: List[Decorator[_, _, _, _]],
                inputContexts: List[Any],
                bindings: List[Map[String, Any]]): Result[Any] = try {
    remainingDecorators match {
      case head :: rest =>
        head.asInstanceOf[Decorator[Any, Any, Any, Any]].wrapFunction(
          ctx,
          (ictx, args) => invoke(ctx, endpoint, entryPoint, routes, rest, ictx :: inputContexts, args :: bindings)
            .asInstanceOf[Result[Nothing]]
        )

      case Nil =>
        endpoint.wrapFunction(ctx, { (ictx: Any, endpointBindings: Map[String, Any]) =>

          val mergedEndpointBindings = endpointBindings ++ ctx.boundPathSegments.mapValues(endpoint.wrapPathSegment)
          val finalBindings = mergedEndpointBindings :: bindings

          entryPoint
            .asInstanceOf[EntryPoint[T, Any]]
            .invoke(routes, ictx :: inputContexts, finalBindings)
            .asInstanceOf[Result[Nothing]]
        })
    }
    // Make sure we wrap any exceptions that bubble up from decorator
    // bodies, so outer decorators do not need to worry about their
    // delegate throwing on them
  }catch{case e: Throwable => Result.Error.Exception(e) }
}

/**
  * A [[RawDecorator]] is a decorator that operates on the raw request and
  * response stream, before and after the primary [[Endpoint]] does it's job.
  */
trait RawDecorator extends Decorator[Response.Raw, Response.Raw, Any, Request]{
  type InputParser[T] = NoOpParser[Any, T, Request]
}


/**
  * An [[HttpEndpoint]] that may return something else than a HTTP response, e.g.
  * a websocket endpoint which may instead return a websocket event handler
  */
trait Endpoint[OuterReturned, InnerReturned, Input, InputContext]
  extends Decorator[OuterReturned, InnerReturned, Input, InputContext]{

  /**
    * What is the path that this particular endpoint matches?
    */
  val path: String
  /**
    * Which HTTP methods does this endpoint support? POST? GET? PUT? Or some
    * combination of those?
    */
  val methods: Seq[String]

  /**
    * Whether or not this endpoint allows matching on sub-paths: does
    * `@endpoint("/foo")` capture the path "/foo/bar/baz"? Useful to e.g. have
    * an endpoint match URLs with paths in a filesystem (real or virtual) to
    * serve files
    */
  def subpath: Boolean = false

  def convertToResultType[T](t: T)
                            (implicit f: Conversion[T, InnerReturned]): InnerReturned = {
    f.f(t)
  }

  /**
    * [[HttpEndpoint]]s are unique among decorators in that they alone can bind
    * path segments to parameters, e.g. binding `/hello/:world` to `(world: Int)`.
    * In order to do so, we need to box up the path segment strings into an
    * [[Input]] so they can later be parsed by [[getParamParser]] into an
    * instance of the appropriate type.
    */
  def wrapPathSegment(s: String): Input

}

/**
  * Annotates a Cask endpoint that returns a HTTP [[Response]]; similar to a
  * [[RawDecorator]] but with additional metadata and capabilities.
  */
trait HttpEndpoint[InnerReturned, Input] extends Endpoint[Response.Raw, InnerReturned, Input, Request]


class NoOpParser[Input, T, InputContext] extends ArgReader[Input, T, InputContext] {
  def arity = 1

  def read(ctx: InputContext, label: String, input: Input) = input.asInstanceOf[T]
}
object NoOpParser{
  implicit def instance[Input, T, InputContext]: NoOpParser[Input, T, InputContext] = new NoOpParser[Input, T, InputContext]
  implicit def instanceAny[T, InputContext]: NoOpParser[Any, T, InputContext] = new NoOpParser[Any, T, InputContext]
  implicit def instanceAnyRequest[T]: NoOpParser[Any, T, Request] = new NoOpParser[Any, T, Request]
}
