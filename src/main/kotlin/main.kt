import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.selects.select
import java.util.*
import javax.security.auth.login.FailedLoginException
import kotlin.concurrent.fixedRateTimer

class PoolEntry<T>(val value: T, var lastUsed: Date = Date()) : AutoCloseable {
    override fun close() {
        try {
            if (value is AutoCloseable) value.close()
        } catch (_: Throwable) {
        }
    }
}

sealed class Result<T>
data class Ok<T>(val value: T) : Result<T>()
data class Err<T>(val error: Throwable) : Result<T>()

class ObjectPool<T>(createNew: () -> T, capacity: Int = 50, private val inactiveTimeBeforeDispose: Long) {
    val reqCh = Channel<Pair<Channel<Unit>, Channel<Result<PoolEntry<T>>>>>()
    val releaseCh = Channel<PoolEntry<T>>()
    val maybeExpiredCh = Channel<PoolEntry<T>>()
    val doDispose = Channel<Unit>()
    val hasDisposed = Channel<Boolean>()
    val available = Stack<PoolEntry<T>>()
    var given = 0

    init {
        launch(CommonPool) {
            while (true) {
                select<Unit> {
                    // an instance returns to pool
                    releaseCh.onReceive { instance ->
                        instance.lastUsed = Date()
                        launch(CommonPool) {
                            delay(inactiveTimeBeforeDispose)
                            maybeExpiredCh.send(instance)
                        }
                        available.add(instance)
                        given--
                    }
                    //// request for an instance
                    reqCh.onReceive { (nack, replyCh) ->
                        if (available.empty()) {
                            try {
                                val instance = PoolEntry(createNew())
                                select<Unit> {
                                    replyCh.onSend(Ok(instance)) { given++ }
                                    nack.onSend(Unit) { available.add(instance) }
                                }

                            } catch (e : Throwable) {
                                select<Unit> {
                                    replyCh.onSend(Err(e)) {}
                                    nack.onSend(Unit) {}
                                }
                            }
                        }
                        else {
                            val instance = available.pop()
                            select {
                                replyCh.onSend(Ok(instance)) { given++ }
                                nack.onSend(Unit) { available.add(instance) }
                            }
                        }
                    }
                }
            }
        }
    }

//// an instance was inactive for too long
//let expiredAlt() =
//maybeExpiredCh ^=> fun instance ->
//if DateTime.UtcNow - instance.LastUsed > inactiveTimeBeforeDispose
//&& List.exists (fun x -> obj.ReferenceEquals(x, instance)) available then
//dispose instance
//loop (available |> List.filter (fun x -> not <| obj.ReferenceEquals(x, instance)), given)
//else loop (available, given)
//
//// the entire pool is disposing
//let disposeAlt() =
//doDispose ^=> fun _ ->
//// dispose all instances that are in pool
//available |> List.iter dispose
//// wait until all given instances are returns to pool and disposing them on the way
//Job.forN (int given) (releaseCh >>- dispose) >>=. hasDisposed *<= ()
//
//if given < capacity then
//// if number of given objects has not reached the capacity, synchronize on request channel as well
//releaseAlt() <|> expiredAlt() <|> disposeAlt() <|> reqAlt()
//else
//releaseAlt() <|> expiredAlt() <|> disposeAlt()
//
//do start (loop ([], 0u))
}
//
//let get() = reqCh *<+->- fun replyCh nack -> (nack, replyCh)
//
///// Applies a function, that returns a Job, on an instance from pool. Returns `Alt` to consume
///// the function result.
//member __.WithInstanceJobChoice (f: 'a -> #Job<Choice<'r, exn>>) : Alt<Choice<'r, exn>> =
//get() ^=> function
//| Ok entry ->
//Job.tryFinallyJobDelay
//<| fun _ -> f entry.Value
//<| releaseCh *<- entry
//| Fail e -> Job.result (Fail e)
//
///// Applies a function, that returns a Job, on an instance from pool. Returns `Alt` to consume
///// the function result.
//member x.WithInstanceJob (f: 'a -> #Job<'r>) : Alt<Choice<'r, exn>> = x.WithInstanceJobChoice (f >> Job.catch)
//
//interface IAsyncDisposable with
//member __.DisposeAsync() = IVar.tryFill doDispose () >>=. hasDisposed
//
//interface IDisposable with
///// Runs disposing asynchronously. Does not wait until the disposing finishes.
//member x.Dispose() = (x :> IAsyncDisposable).DisposeAsync() |> start
//
//type ObjectPool with
///// Applies a function on an instance from pool. Returns the function result.
//member x.WithInstance f = x.WithInstanceJob (fun a -> Job.result (f a))
///// Returns an Async that applies a function on an instance from pool and returns the function result.
//member x.WithInstanceAsync f = x.WithInstance f |> Job.toAsync
///// Applies a function on an instance from pool, synchronously, in the thread in which it's called.
///// Warning! Can deadlock being called from application main thread.
//member x.WithInstanceSync f = x.WithInstance f |> run