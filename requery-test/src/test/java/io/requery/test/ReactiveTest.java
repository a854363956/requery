/*
 * Copyright 2017 requery.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.requery.test;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import io.requery.BlockingEntityStore;
import io.requery.Persistable;
import io.requery.cache.EntityCacheBuilder;
import io.requery.meta.EntityModel;
import io.requery.query.Result;
import io.requery.reactivex.ReactiveEntityStore;
import io.requery.reactivex.ReactiveResult;
import io.requery.reactivex.ReactiveSupport;
import io.requery.sql.Configuration;
import io.requery.sql.ConfigurationBuilder;
import io.requery.sql.EntityDataStore;
import io.requery.sql.Platform;
import io.requery.sql.SchemaModifier;
import io.requery.sql.TableCreationMode;
import io.requery.sql.platform.HSQL;
import io.requery.test.model.Person;
import io.requery.test.model.Phone;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.spi.CachingProvider;
import javax.sql.CommonDataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ReactiveTest extends RandomData {

    protected ReactiveEntityStore<Persistable> data;

    @Before
    public void setup() throws SQLException {
        Platform platform = new HSQL();
        CommonDataSource dataSource = DatabaseType.getDataSource(platform);
        EntityModel model = io.requery.test.model.Models.DEFAULT;

        CachingProvider provider = Caching.getCachingProvider();
        CacheManager cacheManager = provider.getCacheManager();
        Configuration configuration = new ConfigurationBuilder(dataSource, model)
            .useDefaultLogging()
            .setWriteExecutor(Executors.newSingleThreadExecutor())
            .setEntityCache(new EntityCacheBuilder(model)
                .useReferenceCache(true)
                .useSerializableCache(true)
                .useCacheManager(cacheManager)
                .build())
            .build();

        SchemaModifier tables = new SchemaModifier(configuration);
        tables.createTables(TableCreationMode.DROP_CREATE);
        data = ReactiveSupport.toReactiveStore(new EntityDataStore<Persistable>(configuration));
    }

    @After
    public void teardown() {
        if (data != null) {
            data.close();
        }
    }

    @Test
    public void testInsert() throws Exception {
        Person person = randomPerson();
        final CountDownLatch latch = new CountDownLatch(1);
        data.insert(person).subscribe(new Consumer<Person>() {
            @Override
            public void accept(Person person) {
                assertTrue(person.getId() > 0);
                Person cached = data.select(Person.class)
                    .where(Person.ID.equal(person.getId())).get().first();
                assertSame(cached, person);
                latch.countDown();
            }
        });
        latch.await();
    }

    @Test
    public void testDelete() throws Exception {
        Person person = randomPerson();
        data.insert(person).blockingGet();
        data.delete(person).blockingGet();
        Person cached = data.select(Person.class)
            .where(Person.ID.equal(person.getId())).get().firstOrNull();
        assertNull(cached);
    }

    @Test
    public void testInsertCount() throws Exception {
        Person person = randomPerson();
        Observable.just(person)
            .concatMap(new Function<Person, Observable<Person>>() {
            @Override
            public Observable<Person> apply(Person person) {
                return data.insert(person).toObservable();
            }
        });
        Person p = data.insert(person).blockingGet();
        assertTrue(p.getId() > 0);
        int count = data.count(Person.class).get().single().blockingGet();
        assertEquals(1, count);
    }

    @Test
    public void testInsertOneToMany() throws Exception {
        final Person person = randomPerson();
        data.insert(person).map(new Function<Person, Phone>() {
            @Override
            public Phone apply(Person person) {
                Phone phone1 = randomPhone();
                phone1.setOwner(person);
                return phone1;
            }
        }).flatMap(new Function<Phone, Single<?>>() {
            @Override
            public Single<?> apply(Phone phone) {
                return data.insert(phone);
            }
        }).blockingGet();
        assertTrue(person.getPhoneNumbers().toList().size() == 1);
    }

    @Test
    public void testQueryEmpty() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        data.select(Person.class).get().observable()
                .subscribe(new Consumer<Person>() {
            @Override
            public void accept(Person person) throws Exception {
                Assert.fail();
            }
        }, new Consumer<Throwable>() {
            @Override
            public void accept(Throwable throwable) throws Exception {
                Assert.fail();
            }
        }, new Action() {
            @Override
            public void run() throws Exception {
                latch.countDown();
            }
        });
        if (!latch.await(1, TimeUnit.SECONDS)) {
            Assert.fail();
        }
    }

    @Test
    public void testQueryObservable() throws Exception {
        for (int i = 0; i < 30; i++) {
            Person person = randomPerson();
            data.insert(person).blockingGet();
        }
        final List<Person> people = new ArrayList<>();
        data.select(Person.class).limit(50).get()
            .observable()
            .subscribe(new Consumer<Person>() {
            @Override
            public void accept(Person person) {
                people.add(person);
            }
        });
        assertEquals(30, people.size());
    }

    @Test
    public void testQuerySelfObservable() throws Exception {
        final AtomicInteger count = new AtomicInteger();
        data.select(Person.class).get().observableResult().subscribe(
            new Consumer<Result<Person>>() {
            @Override
            public void accept(Result<Person> persons) {
                count.incrementAndGet();
            }
        });
        data.insert(randomPerson()).blockingGet();
        data.insert(randomPerson()).blockingGet();
        assertEquals(3, count.get());
    }

    @Test
    public void testQuerySelfObservableMap() throws Exception {
        final AtomicInteger count = new AtomicInteger();
        Disposable disposable = data.select(Person.class).limit(2).get().observableResult()
            .flatMap(new Function<ReactiveResult<Person>, Observable<Person>>() {
                @Override
                public Observable<Person> apply(ReactiveResult<Person> persons) {
                    return persons.observable();
                }
            }).subscribe(new Consumer<Person>() {
                @Override
                public void accept(Person persons) {
                    count.incrementAndGet();
                }
            });
        data.insert(randomPerson()).blockingGet();
        data.insert(randomPerson()).blockingGet();
        assertEquals(3, count.get());
        disposable.dispose();
    }

    @Test
    public void testSelfObservableDelete() throws Exception {
        final AtomicInteger count = new AtomicInteger();
        Disposable disposable = data.select(Person.class).get().observableResult().subscribe(
            new Consumer<Result<Person>>() {
                @Override
                public void accept(Result<Person> persons) {
                    count.incrementAndGet();
                }
            });
        Person person = randomPerson();
        data.insert(person).blockingGet();
        data.delete(person).blockingGet();
        assertEquals(3, count.get());
        disposable.dispose();
    }

    @Test
    public void testSelfObservableDeleteQuery() throws Exception {
        final AtomicInteger count = new AtomicInteger();
        Disposable disposable = data.select(Person.class).get().observableResult().subscribe(
            new Consumer<Result<Person>>() {
                @Override
                public void accept(Result<Person> persons) {
                    count.incrementAndGet();
                }
            });
        Person person = randomPerson();
        data.insert(person).blockingGet();
        assertEquals(2, count.get());
        int rows = data.delete(Person.class).get().value();
        assertEquals(3, count.get());
        disposable.dispose();
        assertEquals(rows, 1);
    }

    @Test
    public void testQuerySelfObservableRelational() throws Exception {
        final AtomicInteger count = new AtomicInteger();
        Disposable disposable = data.select(Person.class).get().observableResult().subscribe(
            new Consumer<Result<Person>>() {
                @Override
                public void accept(Result<Person> persons) {
                    count.incrementAndGet();
                }
            });
        Person person = randomPerson();
        data.insert(person).blockingGet();
        Phone phone = randomPhone();
        person.getPhoneNumbers().add(phone);
        data.update(person).blockingGet();
        data.delete(phone).blockingGet();
        assertEquals(4, count.get());
        disposable.dispose();
    }

    @Test
    public void testQueryObservableFromEntity() throws Exception {
        final Person person = randomPerson();
        data.insert(person).map(new Function<Person, Phone>() {
            @Override
            public Phone apply(Person person) {
                Phone phone1 = randomPhone();
                phone1.setOwner(person);
                return phone1;
            }
        }).flatMap(new Function<Phone, Single<?>>() {
            @Override
            public Single<?> apply(Phone phone) {
                return data.insert(phone);
            }
        }).blockingGet();
        int count = person.getPhoneNumbers().toList().size();
        assertEquals(1, count);
    }

    @Test
    public void testRunInTransaction() {
        final Person person = randomPerson();
        data.runInTransaction(new io.requery.util.function.Function<BlockingEntityStore<Persistable>, Boolean>() {
            @Override
            public Boolean apply(BlockingEntityStore<Persistable> blocking) {
                blocking.insert(person);
                blocking.update(person);
                blocking.delete(person);
                return true;
            }
        }).blockingGet();
        assertEquals(0, data.count(Person.class).get().value().intValue());

        final Person person2 = randomPerson();
        data.runInTransaction(new io.requery.util.function.Function<BlockingEntityStore<Persistable>, Boolean>() {
            @Override
            public Boolean apply(BlockingEntityStore<Persistable> blocking) {
                blocking.insert(person2);
                return true;
            }
        }).blockingGet();
        assertEquals(1, data.count(Person.class).get().value().intValue());
    }

    @Test
    public void testRunInTransactionFromBlocking() {
        final BlockingEntityStore<Persistable> blocking = data.toBlocking();
        Completable.fromCallable(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                blocking.runInTransaction(new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        final Person person = randomPerson();
                        blocking.insert(person);
                        blocking.update(person);
                        return null;
                    }
                });
                return null;
            }
        }).subscribe();
        assertEquals(1, data.count(Person.class).get().value().intValue());
    }

    @Test
    public void testQueryObservablePull() throws Exception {
        for (int i = 0; i < 36; i++) {
            Person person = randomPerson();
            data.insert(person).blockingGet();
        }
        final List<Person> people = new ArrayList<>();
        data.select(Person.class).get()
            .flowable()
            .subscribeOn(Schedulers.trampoline())
            .subscribe(new Subscriber<Person>() {
                Subscription s;
                @Override
                public void onSubscribe(Subscription s) {
                    this.s = s;
                    s.request(10);
                }

                @Override
                public void onComplete() {
                }

                @Override
                public void onError(Throwable e) {
                }

                @Override
                public void onNext(Person person) {
                    people.add(person);
                    if (people.size() % 10 == 0 && people.size() > 1) {
                        s.request(10);
                    }
                }
            });
        assertEquals(36, people.size());
    }
}
