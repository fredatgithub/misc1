package misc1.commons.concurrent.ctree;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import misc1.commons.ExceptionUtils;
import misc1.commons.Result;

public final class ComputationTreeComputer {
    private final Executor e;

    public ComputationTreeComputer(Executor e) {
        this.e = e;
    }

    private enum StatusStatus {
        UNSTARTED,
        STARTED,
        DONE;
    }
    private class Status<V> {
        private final Function<ImmutableList<Object>, V> postProcess;
        private final ImmutableList<Status<?>> children;
        private final ImmutableList.Builder<Result<?>> childrenResultsBuilder = ImmutableList.builder();
        private int childrenResultsSize = 0;

        public Status(ComputationTree<V> tree, ImmutableList<Status<?>> children) {
            this.postProcess = tree.postProcess;
            this.children = children;
        }

        private StatusStatus status = StatusStatus.UNSTARTED;
        private final Set<Status<?>> outwards = Sets.newHashSet();
        private Result<V> result;

        public synchronized Result<V> getResultOrListen(Status<?> outward) {
            if(status != StatusStatus.DONE) {
                outwards.add(outward);
                return null;
            }
            return result;
        }

        public synchronized void check() {
            switch(status) {
                case UNSTARTED:
                    while(childrenResultsSize < children.size()) {
                        Status<?> child = children.get(childrenResultsSize);
                        Result<?> childResult = child.getResultOrListen(this);
                        if(childResult == null) {
                            return;
                        }
                        childrenResultsBuilder.add(childResult);
                        ++childrenResultsSize;
                    }

                    // All children finished, time to run, asynchronously (not
                    // under synch(this))
                    final ImmutableList<Result<?>> childrenResults = childrenResultsBuilder.build();
                    e.execute(() -> {
                        // Force all the children results and then postProcess,
                        // all inside callable so we inherit child failures.
                        Result<V> result = Result.newFromCallable(() -> {
                            ImmutableList.Builder<Object> childrenBuilder = ImmutableList.builder();
                            for(Result<?> childrenResult : childrenResults) {
                                childrenBuilder.add(childrenResult.getCommute());
                            }
                            return postProcess.apply(childrenBuilder.build());
                        });

                        // Tag the result.
                        complete(result);
                    });
                    status = StatusStatus.STARTED;
                    break;

                case STARTED:
                case DONE:
                    break;
            }
        }

        private synchronized void complete(Result<V> newResult) {
            result = newResult;
            status = StatusStatus.DONE;
            notifyAll();

            for(final Status<?> outward : outwards) {
                // check, but not under synch(this)
                e.execute(() -> outward.check());
            }
        }

        public synchronized Result<V> await() throws InterruptedException {
            while(status != StatusStatus.DONE) {
                wait();
            }
            return result;
        }
    }

    private final Object lock = new Object();
    private final Map<ComputationTree<?>, Status<?>> statuses = Maps.newIdentityHashMap();

    private <V> Status<V> vivify(ComputationTree<V> tree) {
        synchronized(lock) {
            return vivifyHelper(tree);
        }
    }

    private <V> Status<V> vivifyHelper(ComputationTree<V> tree) {
        Status<V> already = (Status<V>)statuses.get(tree);
        if(already != null) {
            return already;
        }
        ImmutableList.Builder<Status<?>> childrenBuilder = ImmutableList.builder();
        for(ComputationTree<?> childTree : tree.children) {
            childrenBuilder.add(vivifyHelper(childTree));
        }
        ImmutableList<Status<?>> children = childrenBuilder.build();
        Status<V> ret = new Status<V>(tree, children);
        // avoid doing anything even marginally interesting under synch(lock)
        e.execute(() -> ret.check());
        statuses.put(tree, ret);
        return ret;
    }

    public void start(ComputationTree<?> tree) {
        vivify(tree);
    }

    public <V> Result<V> await(ComputationTree<V> tree) {
        try {
            return vivify(tree).await();
        }
        catch(InterruptedException e) {
            throw ExceptionUtils.commute(e);
        }
    }
}
