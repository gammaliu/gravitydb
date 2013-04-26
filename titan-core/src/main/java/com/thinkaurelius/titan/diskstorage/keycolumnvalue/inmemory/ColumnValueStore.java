package com.thinkaurelius.titan.diskstorage.keycolumnvalue.inmemory;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.*;
import com.thinkaurelius.titan.diskstorage.util.ByteBufferUtil;
import com.thinkaurelius.titan.diskstorage.util.NoLock;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * (c) Matthias Broecheler (me@matthiasb.com)
 */

class ColumnValueStore {

    private static final double SIZE_THRESHOLD = 0.66;

    private Data data;

    public ColumnValueStore() {
        data = new Data(new CacheEntry[0],0);
    }

    boolean isEmpty(StoreTransaction txh) {
        Lock lock = getLock(txh);
        lock.lock();
        try {
            return data.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    ByteBuffer get(ByteBuffer column, StoreTransaction txh) {
        Lock lock = getLock(txh);
        lock.lock();
        try {
            int index = data.getIndex(column);
            if (index>=0) return data.get(index).getValue();
            else return null;
        } finally {
            lock.unlock();
        }
    }

    List<Entry> getSlice(KeySliceQuery query, StoreTransaction txh) {
        Lock lock = getLock(txh);
        lock.lock();
        try {
            Data datacp = data;
            int start = datacp.getIndex(query.getSliceStart());
            if (start<0) start = (-start-1);
            int end = datacp.getIndex(query.getSliceEnd());
            if (end<0) end = (-end-1);
            if (start<end) {
                List<Entry> result = new ArrayList<Entry>(end-start);
                for (int i=start;i<end;i++) {
                    if (query.hasLimit() && result.size()>=query.getLimit()) break;
                    result.add(datacp.get(i));
                }
                return result;
            } else {
                return ImmutableList.of();
            }
        } finally {
            lock.unlock();
        }
    }


    synchronized void mutate(List<Entry> additions, List<ByteBuffer> deletions, StoreTransaction txh) {
        //Prepare data
        CacheEntry[] add;
        if (!additions.isEmpty()) {
            add = new CacheEntry[additions.size()];
            int pos = 0;
            for (Entry e : additions) {
                add[pos]=new CacheEntry(e);
                pos++;
            }
            Arrays.sort(add);
        } else add=new CacheEntry[0];

        //Filter out deletions that are also added
        ByteBuffer[] del;
        if (!deletions.isEmpty()) {
            Iterator<ByteBuffer> iter = deletions.iterator();
            while (iter.hasNext()) {
                if (Arrays.binarySearch(add,new SimpleEntry(iter.next(),null))>=0) {
                    iter.remove();
                }
            }
            del = deletions.toArray(new ByteBuffer[deletions.size()]);
            Arrays.sort(del,new Comparator<ByteBuffer>() {

                @Override
                public int compare(ByteBuffer byteBuffer, ByteBuffer byteBuffer2) {
                    return ByteBufferUtil.compare(byteBuffer, byteBuffer2);
                }
            });
        } else del = new ByteBuffer[0];

        Lock lock = getLock(txh);
        lock.lock();
        try {
            CacheEntry[] olddata = data.array;
            CacheEntry[] newdata = new CacheEntry[olddata.length+add.length];

            //Merge sort
            int i=0,iold=0, iadd=0, idel=0;
            while (iold<olddata.length) {
                CacheEntry e = olddata[iold];
                iold++;
                //Compare with additions
                if (iadd<add.length) {
                    int compare = e.compareTo(add[iadd]);
                    if (compare>=0) {
                        e=add[iadd];
                        iadd++;
                    }
                    if (compare>0) iold--;
                }
                //Compare with deletions
                if (idel<del.length) {
                    int compare = ByteBufferUtil.compare(e.getColumn(),del[idel]);
                    if (compare==0) e=null;
                    if (compare>=0) idel++;
                }
                if (e!=null) {
                    newdata[i]=e;
                    i++;
                }
            }
            while (iadd<add.length) {
                newdata[i]=add[iadd];
                i++; iadd++;
            }

            if (i*1.0/newdata.length<SIZE_THRESHOLD) {
                //shrink array to free space
                CacheEntry[] tmpdata = newdata;
                newdata = new CacheEntry[i];
                System.arraycopy(tmpdata,0,newdata,0,i);
            }
            data = new Data(newdata,i);
        } finally {
            lock.unlock();
        }
    }


    private ReentrantLock lock=null;
    private Lock getLock(StoreTransaction txh) {
        if (txh.getConsistencyLevel()== ConsistencyLevel.KEY_CONSISTENT) {
            if (lock==null) {
                synchronized (this) {
                    if (lock==null) {
                        lock=new ReentrantLock();
                    }
                }
            }
            return lock;
        } else return NoLock.INSTANCE;
    }

    private static class Data {

        final CacheEntry[] array;
        final int size;

        Data(final CacheEntry[] array, final int size) {
            Preconditions.checkArgument(size>=0 && size<=array.length);
            assert isSorted();
            this.array =array;
            this.size=size;
        }

        boolean isEmpty() {
            return size==0;
        }

        int getIndex(ByteBuffer column) {
            return Arrays.binarySearch(array,0,size,new SimpleEntry(column,null));
        }

        Entry get(int index) {
            return array[index];
        }

        boolean isSorted() {
            for (int i=1;i<size;i++) {
                if (!(array[i].compareTo(array[i-1])>0)) return false;
            }
            return true;
        }

    }


}