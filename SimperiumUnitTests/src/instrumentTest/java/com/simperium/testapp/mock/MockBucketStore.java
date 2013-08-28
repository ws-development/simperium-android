package com.simperium.testapp.mock;

import com.simperium.client.Syncable;
import com.simperium.client.Bucket;
import com.simperium.client.BucketSchema.Index;

import com.simperium.client.Query;
import com.simperium.storage.StorageProvider;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Collections;

import android.os.CancellationSignal;

public class MockBucketStore<T extends Syncable> implements StorageProvider.BucketStore<T> {

    private Map<String, T> objects = Collections.synchronizedMap(new HashMap<String, T>(32));

    @Override
    public void prepare(Bucket<T> bucket){
        // noop
    }

    /**
     * Add/Update the given object
     */
    @Override
    public void save(T object, List<Index> indexes){
        objects.put(object.getSimperiumKey(), object);
    }

    /**
     * Remove the given object from the storage
     */
    @Override
    public void delete(T object){
        objects.remove(object.getSimperiumKey());
    }

    /**
     * Delete all objects from storage
     */
    @Override
    public void reset(){
        objects.clear();
    }

    /**
     * Get an object with the given key
     */
    @Override
    public T get(String key){
        return objects.get(key);
    }

    /**
     * Get a cursor to all the objects
     */
    @Override
    public Bucket.ObjectCursor<T> all(CancellationSignal cancelSignal){
        return null;
    }

    /**
     * Search
     */
    @Override
    public Bucket.ObjectCursor<T> search(Query query, CancellationSignal cancelSignal){
        return null;
    }

    /**
     * Count
     */
    @Override
    public int count(Query query, CancellationSignal cancelSignal){
        return 0;
    }
}