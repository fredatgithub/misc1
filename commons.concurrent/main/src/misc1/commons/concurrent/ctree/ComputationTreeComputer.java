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

        public Status(ComputationTree<V> tree, ImmutableList<Status<?>> children) {
            this.postProcess = tree.postProcess;
            this.children = children;
        }

        private StatusStatus status = StatusStatus.UNSTARTED;
        private final Set<Status<?>> outwards = Sets.newHashSet();
        private Result<V> result;

        public synchronized void checkStart() {
            if(status != StatusStatus.UNSTARTED) {
                return;
            }

            ImmutableList.Builder<Result<?>> childrenResultsBuilder = ImmutableList.builder();
            for(Status<?> child : children) {
                synchronized(child) {
                    switch(child.status) {
                        case DONE:
                            childrenResultsBuilder.add(child.result);
                            break;

                        default:
                            return;
                    }
                }
            }
            final ImmutableList<Result<?>> childrenResults = childrenResultsBuilder.build();
            e.execute(() -> complete(Result.newFromCallable(() -> {
                ImmutableList.Builder<Object> childrenBuilder = ImmutableList.builder();
                for(Result<?> childrenResult : childrenResults) {
                    childrenBuilder.add(childrenResult.getCommute());
                }
                return postProcess.apply(childrenBuilder.build());
            })));
            status = StatusStatus.STARTED;
        }

        private synchronized void complete(Result<V> newResult) {
            if(status != StatusStatus.STARTED) {
                throw new IllegalStateException();
            }

            status = StatusStatus.DONE;
            result = newResult;
            notifyAll();

            for(final Status<?> outward : outwards) {
                submitCheck(outward);
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

    private void submitCheck(final Status<?> status) {
        e.execute(() -> status.checkStart());
    }

    private <V> Status<V> vivify(ComputationTree<V> tree) {
        synchronized(lock) {
            return vivifyHelper(tree);
        }
    }

    private <V> Status<V> vivifyHelper(ComputationTree<V> tree) {
        Status<V> ret = (Status<V>)statuses.get(tree);
        if(ret != null) {
            return ret;
        }
        ImmutableList.Builder<Status<?>> childrenBuilder = ImmutableList.builder();
        for(ComputationTree<?> childTree : tree.children) {
            childrenBuilder.add(vivifyHelper(childTree));
        }
        ImmutableList<Status<?>> children = childrenBuilder.build();
        ret = new Status<V>(tree, children);
        for(Status<?> child : children) {
            synchronized(child) {
                child.outwards.add(ret);
            }
        }
        submitCheck(ret);
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
