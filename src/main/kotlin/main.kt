import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.selects.select
import java.util.*

class PoolEntry<T>(val value: T, var lastUsed: Date = Date()) : AutoCloseable {
    override fun close() {
        if (value is AutoCloseable)
            try {
                value.close()
            } catch (_: Throwable) {
            }
    }
}

class ObjectPool<T>(createNew: () -> T, capacity: Int = 50, private val inactiveTimeBeforeDisposeMillis: Long) {
    val reqCh = Channel<Channel<PoolEntry<T>>>()
    val releaseCh = Channel<PoolEntry<T>>()
    val maybeExpiredCh = Channel<PoolEntry<T>>()
    val doDispose = Channel<Unit>()
    val hasDisposed = Channel<Unit>()
    val available = Stack<PoolEntry<T>>()
    var given = 0

    private fun <T> Stack<T>.tryPop() = if (empty()) null else pop()
    private fun <T> close(x: T) {
        if (x is AutoCloseable) x.close()
    }

    init {
        launch(CommonPool) {
            while (true) {
                select<Unit> {
                    // an instance returns to pool
                    releaseCh.onReceive { instance ->
                        instance.lastUsed = Date()
                        launch(CommonPool) {
                            delay(inactiveTimeBeforeDisposeMillis)
                            maybeExpiredCh.send(instance)
                        }
                        available.add(instance)
                        given--
                    }
                    // if number of given objects has not reached the capacity, synchronize on request channel as well
                    if (given < capacity)
                    // request for an instance
                        reqCh.onReceive { replyCh ->
                            replyCh.send(available.tryPop() ?: PoolEntry(createNew()))
                            given++
                        }
                    // an instance was inactive for too long
                    maybeExpiredCh.onReceive { instance ->
                        if (Date().time - instance.lastUsed.time > inactiveTimeBeforeDisposeMillis
                            && available.any { it === instance }) {
                            close(instance)
                            available.removeIf { it === instance }
                        }
                        // the entire pool is disposing
                        doDispose.onReceive {
                            // dispose all instances that are in pool
                            available.forEach { close(it) }
                            // wait until all given instances are returned to the pool and disposing them on the way
                            (1..given).forEach { close(releaseCh.receive()) }
                            hasDisposed.send(Unit)
                        }
                    }
                }
            }
        }
    }

    suspend fun get() : T {
        val replyCh = Channel<PoolEntry<T>>()
        reqCh.send(replyCh)
        return replyCh.receive().value
    }
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
}