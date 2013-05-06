package org.devtcg.sqliteserver.impl.binder;

import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import org.devtcg.sqliteserver.impl.ExecutorHelper;
import org.devtcg.sqliteserver.impl.SQLiteExecutor;
import org.devtcg.sqliteserver.impl.binder.protocol.AbstractCommandMessage;
import org.devtcg.sqliteserver.impl.binder.protocol.MethodName;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

import static android.os.IBinder.DeathRecipient;
import static org.devtcg.sqliteserver.impl.binder.protocol.AcquireCommand.AcquireHandler;
import static org.devtcg.sqliteserver.impl.binder.protocol.BeginTransactionCommand.BeginTransactionHandler;
import static org.devtcg.sqliteserver.impl.binder.protocol.DeleteCommand.DeleteHandler;
import static org.devtcg.sqliteserver.impl.binder.protocol.EndTransactionCommand.EndTransactionHandler;
import static org.devtcg.sqliteserver.impl.binder.protocol.ExecSQLCommand.ExecSQLHandler;
import static org.devtcg.sqliteserver.impl.binder.protocol.InsertCommand.InsertHandler;
import static org.devtcg.sqliteserver.impl.binder.protocol.RawQueryCommand.RawQueryHandler;
import static org.devtcg.sqliteserver.impl.binder.protocol.ReleaseCommand.ReleaseHandler;
import static org.devtcg.sqliteserver.impl.binder.protocol.SetTransactionSuccessfulCommand.SetTransactionSuccessfulHandler;
import static org.devtcg.sqliteserver.impl.binder.protocol.UpdateCommand.UpdateHandler;

/**
 * Unflattens server command messages expressed in a Bundle and delegates them to the
 * {@link SQLiteExecutor}.  Then, packages up the response back into a Bundle for
 * {@link AbstractBinderClient} to process on the other end.
 * <p>
 * Requests sent here will be executed on a dedicated thread per client connection.  This is
 * to preserve the thread affinity normally observed with a local
 * {@link android.database.sqlite.SQLiteDatabase} instance.
 */
public class ServerImpl {
    private final String mTag;
    private final SQLiteExecutor mExecutor;
    private final String mServerName;

    /**
     * Each unique client connection gets its own separate server state simulating the
     * ThreadLocal nature of SQLiteDatabase through our IPC interface.  For example,
     * transaction isolation is guaranteed on a per thread, per open database basis
     * with SQLiteDatabase, so we must simulate this when even multiple connections may
     * be served by the same Binder thread.
     * <p>
     * TODO: Currently we're not ensuring that each unique client runs on its own thread,
     * breaking an important part of transaction isolation.
     */
    private final Map<IBinder, ServerState> mServerStateMap =
            new ConcurrentHashMap<IBinder, ServerState>();

    public ServerImpl(String logTag, SQLiteExecutor executor, String serverName) {
        mTag = logTag;
        mExecutor = executor;
        mServerName = serverName;
    }

    public SQLiteExecutor getExecutor() {
        return mExecutor;
    }

    /**
     * Used by the service implementation to trigger close.  Need to revisit
     * this policy at some point in the future.  It's hard to decide whether it's
     * best to have the ContentProvider and Service implementations try to match
     * each other semantically or if there is cause to offer a more flexible model
     * with the Service implementation.
     * <p>
     * The ContentProvider currently never closes the database, and never shuts down, per usual
     * with the ContentProvider paradigm.
     */
    public void closeDatabase() {
        mExecutor.close();
    }

    public ServerState getServerState(BinderHandle clientHandle) {
        return mServerStateMap.get(clientHandle.asBinder());
    }

    public void initServerState(BinderHandle clientHandle)
            throws SQLiteServerProtocolException, RemoteException {
        if (getServerState(clientHandle) != null) {
            throw new SQLiteServerProtocolException("Refusing to handle multiple " +
                    "acquire operations");
        }

        // Handle cleanup if the client disappears.
        ClientDiedHandler deathHandler = new ClientDiedHandler(clientHandle);
        clientHandle.asBinder().linkToDeath(deathHandler, 0);

        ServerState state = new ServerState();
        state.deathRecipient = deathHandler;
        state.clientHandle = clientHandle;
        state.executor = ExecutorHelper.createThreadAffinityExecutor();
        mServerStateMap.put(clientHandle.asBinder(), state);
    }

    public void performRelease(ServerState state) {
        if (state != null) {
            state.executor.shutdown();
            state.clientHandle.asBinder().unlinkToDeath(state.deathRecipient, 0);
            endTransactionsIfNecessary(state);
            mServerStateMap.remove(state.clientHandle.asBinder());
        }
    }

    private void endTransactionsIfNecessary(ServerState state) {
        if (state.numTransactions > 0) {
            Log.i(mTag, "Forcefully ending " + state.numTransactions + " transactions");
            while (state.numTransactions > 0) {
                // Note that if the client had eeked out a setTransactionSuccessful before
                // dying this will actually commit instead of rollback.
                getExecutor().endTransaction();
                state.numTransactions--;
            }
        }
    }

    /**
     * This code path smells a little too heavy for individual database operations (consider
     * a large number of inserts).  Need to benchmark the performance versus ContentProvider
     * and also versus SQLiteDatabase.
     */
    public Bundle onTransact(Bundle request) {
        request.setClassLoader(getClass().getClassLoader());
        MethodName methodName = null;
        try {
            int methodNameOrdinal = BundleUtils.getIntOrThrow(request,
                    AbstractCommandMessage.KEY_METHOD_NAME);
            methodName = MethodName.values()[methodNameOrdinal];
            return runOnTransact(methodName, request);
        } catch (RuntimeException e) {
            // TODO: Handle this explicitly; right now we're relying on the fact that we're
            // being wrapped inside of either a Service or a ContentProvider call which will
            // transmit some exceptions for us.
            Log.e(mTag, "Error executing " + methodName, e);
            throw e;
        } catch (SQLiteServerProtocolException e) {
            StringBuilder message = new StringBuilder();
            message.append("Protocol exception: ").append(e);
            if (methodName != null) {
                message.append(", dropping method: ").append(methodName);
            }
            Log.w(mTag, message.toString());

            // TODO: Send back an error!
            return null;
        }
    }

    private Bundle runOnTransact(MethodName methodName, Bundle request)
            throws SQLiteServerProtocolException {
        switch (methodName) {
            // Acquire can run on the Binder thread since it doesn't interact with
            // SQLiteDatabase.  It's used to set up the dedicated thread executor so it's
            // simplest to keep it out of that code path.
            case ACQUIRE:
                return doOnTransact(methodName, request);
            default:
                return runOnDedicatedThread(methodName, request);
        }
    }

    private Bundle runOnDedicatedThread(final MethodName methodName, final Bundle request)
            throws SQLiteServerProtocolException {
        BinderHandle clientHandle = BundleUtils.getParcelableOrThrow(
                request, AbstractCommandMessage.KEY_CLIENT_BINDER);
        ServerState state = getServerState(clientHandle);
        if (state == null) {
            throw new SQLiteServerProtocolException("No client state found for clientHandle=" +
                    clientHandle);
        }

        try {
            return state.executor.runSynchronously(new Callable<Bundle>() {
                @Override
                public Bundle call() throws Exception {
                    Log.d(mTag, "Invoking " + methodName + " on thread " +
                            Thread.currentThread().getId());
                    return doOnTransact(methodName, request);
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (SQLiteServerProtocolException e) {
            throw e;
        } catch (Exception e) {
            // This represents a programmer error in AndroidSQLiteServer.
            throw new RuntimeException(e);
        }
    }

    private Bundle doOnTransact(MethodName methodName, Bundle request)
            throws SQLiteServerProtocolException {
        switch (methodName) {
            case ACQUIRE:
                return new AcquireHandler(this).handle(request);
            case RELEASE:
                return new ReleaseHandler(this).handle(request);
            case BEGIN_TRANSACTION:
                return new BeginTransactionHandler(this).handle(request);
            case SET_TRANSACTION_SUCCESSFUL:
                return new SetTransactionSuccessfulHandler(this).handle(request);
            case END_TRANSACTION:
                return new EndTransactionHandler(this).handle(request);
            case RAW_QUERY:
                return new RawQueryHandler(this, mServerName).handle(request);
            case EXEC_SQL:
                return new ExecSQLHandler(this).handle(request);
            case INSERT:
                return new InsertHandler(this).handle(request);
            case UPDATE:
                return new UpdateHandler(this).handle(request);
            case DELETE:
                return new DeleteHandler(this).handle(request);
            default:
                throw new IllegalArgumentException("Undeclared methodName=" + methodName);
        }
    }

    private class ClientDiedHandler implements DeathRecipient {
        private final BinderHandle mClientHandle;

        public ClientDiedHandler(BinderHandle clientHandle) {
            mClientHandle = clientHandle;
        }

        @Override
        public void binderDied() {
            Log.w(mTag, "Unexpected client death (clientHandle=" + mClientHandle + ")");
            performRelease(getServerState(mClientHandle));
        }
    }
}
