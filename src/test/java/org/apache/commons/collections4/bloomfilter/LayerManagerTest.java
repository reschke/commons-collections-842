/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.collections4.bloomfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class LayerManagerTest {

    private Shape shape = Shape.fromKM(17, 72);

    @ParameterizedTest
    @ValueSource(ints = {4, 10, 2, 1})
    public void testAdvanceOnCount(int breakAt) {
        Predicate<LayerManager> underTest = LayerManager.ExtendCheck.advanceOnCount(breakAt);
        LayerManager layerManager = testingBuilder().build();
        for (int i = 0; i < breakAt - 1; i++) {
            assertFalse(underTest.test(layerManager), "at " + i);
            layerManager.getTarget().merge(TestingHashers.FROM1);
        }
        assertTrue(underTest.test(layerManager));
    }

    @Test
    public void testAdvanceOnCountInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> LayerManager.ExtendCheck.advanceOnCount(0));
        assertThrows(IllegalArgumentException.class, () -> LayerManager.ExtendCheck.advanceOnCount(-1));
    }

    @Test
    public void testAdvanceOnPopulated() {
        Predicate<LayerManager> underTest = LayerManager.ExtendCheck.advanceOnPopulated();
        LayerManager layerManager = testingBuilder().build();
        assertFalse(underTest.test(layerManager));
        layerManager.getTarget().merge(TestingHashers.FROM1);
        assertTrue(underTest.test(layerManager));
    }

    @Test
    public void testAdvanceOnSaturation() {
        Double maxN = shape.estimateMaxN();
        int hashStart = 0;
        Predicate<LayerManager> underTest = LayerManager.ExtendCheck.advanceOnSaturation(maxN);
        LayerManager layerManager = testingBuilder().build();
        while (layerManager.getTarget().getShape().estimateN(layerManager.getTarget().cardinality()) < maxN) {
            assertFalse(underTest.test(layerManager));
            layerManager.getTarget().merge(new IncrementingHasher(hashStart, shape.getNumberOfHashFunctions()));
            hashStart+=shape.getNumberOfHashFunctions();
        }
        assertTrue(underTest.test(layerManager));
        assertThrows(IllegalArgumentException.class, () -> LayerManager.ExtendCheck.advanceOnSaturation(0));
        assertThrows(IllegalArgumentException.class, () -> LayerManager.ExtendCheck.advanceOnSaturation(-1));
    }

    @Test
    public void testBuilder() {
        LayerManager.Builder underTest = LayerManager.builder();
        NullPointerException npe = assertThrows(NullPointerException.class, () -> underTest.build());
        assertTrue(npe.getMessage().contains("Supplier must not be null"));
        underTest.setSupplier(() -> null).setCleanup(null);
        npe = assertThrows(NullPointerException.class, () -> underTest.build());
        assertTrue(npe.getMessage().contains("Cleanup must not be null"));
        underTest.setCleanup(x -> {
        }).setExtendCheck(null);
        npe = assertThrows(NullPointerException.class, () -> underTest.build());
        assertTrue(npe.getMessage().contains("ExtendCheck must not be null"));

        npe = assertThrows(NullPointerException.class, () -> LayerManager.builder().setSupplier(() -> null).build());
        assertTrue(npe.getMessage().contains("filterSupplier returned null."));

    }

    @Test
    public void testClear() {
        LayerManager underTest = LayerManager.builder().setSupplier(() -> new SimpleBloomFilter(shape)).build();
        underTest.getTarget().merge(TestingHashers.randomHasher());
        underTest.next();
        underTest.getTarget().merge(TestingHashers.randomHasher());
        underTest.next();
        underTest.getTarget().merge(TestingHashers.randomHasher());
        assertEquals(3, underTest.getDepth());
        underTest.clear();
        assertEquals(1, underTest.getDepth());
        assertEquals(0, underTest.getTarget().cardinality());
    }

    @Test
    public void testCopy() {
        LayerManager underTest = LayerManager.builder().setSupplier(() -> new SimpleBloomFilter(shape)).build();
        underTest.getTarget().merge(TestingHashers.randomHasher());
        underTest.next();
        underTest.getTarget().merge(TestingHashers.randomHasher());
        underTest.next();
        underTest.getTarget().merge(TestingHashers.randomHasher());
        assertEquals(3, underTest.getDepth());

        LayerManager copy = underTest.copy();
        assertNotSame(underTest, copy);
        // object equals not implemented
        assertNotEquals(underTest, copy);

        assertEquals(underTest.getDepth(), copy.getDepth());
        assertTrue(
                underTest.forEachBloomFilterPair(copy, (x, y) -> Arrays.equals(x.asBitMapArray(), y.asBitMapArray())));
    }

    @Test
    public void testForEachBloomFilter() {
        LayerManager underTest = LayerManager.builder().setSupplier(() -> new SimpleBloomFilter(shape))
                .setExtendCheck(LayerManager.ExtendCheck.advanceOnPopulated()).build();

        List<BloomFilter> lst = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            BloomFilter bf = new SimpleBloomFilter(shape);
            bf.merge(TestingHashers.randomHasher());
            lst.add(bf);
            underTest.getTarget().merge(bf);
        }
        List<BloomFilter> lst2 = new ArrayList<>();
        underTest.forEachBloomFilter(lst2::add);
        assertEquals(10, lst.size());
        assertEquals(10, lst2.size());
        for (int i = 0; i < lst.size(); i++) {
            assertArrayEquals(lst.get(i).asBitMapArray(), lst2.get(i).asBitMapArray());
        }
    }

    @Test
    public void testGet() {
        SimpleBloomFilter f = new SimpleBloomFilter(shape);
        LayerManager underTest = LayerManager.builder().setSupplier(() -> f).build();
        assertEquals(1, underTest.getDepth());
        assertSame(f, underTest.get(0));
        assertThrows(NoSuchElementException.class, () -> underTest.get(-1));
        assertThrows(NoSuchElementException.class, () -> underTest.get(1));
    }

    private LayerManager.Builder testingBuilder() {
        return LayerManager.builder().setSupplier(() -> new SimpleBloomFilter(shape));
    }

    @Test
    public void testNeverAdvance() {
        Predicate<LayerManager> underTest = LayerManager.ExtendCheck.neverAdvance();
        LayerManager layerManager = testingBuilder().build();
        assertFalse(underTest.test(layerManager));
        for (int i = 0; i < 10; i++) {
            layerManager.getTarget().merge(TestingHashers.randomHasher());
            assertFalse(underTest.test(layerManager));
        }
    }

    @Test
    public void testNextAndGetDepth() {
        LayerManager underTest = LayerManager.builder().setSupplier(() -> new SimpleBloomFilter(shape)).build();
        assertEquals(1, underTest.getDepth());
        underTest.getTarget().merge(TestingHashers.randomHasher());
        assertEquals(1, underTest.getDepth());
        underTest.next();
        assertEquals(2, underTest.getDepth());
    }

    @Test
    public void testNoCleanup() {
        Consumer<LinkedList<BloomFilter>> underTest = LayerManager.Cleanup.noCleanup();
        LinkedList<BloomFilter> list = new LinkedList<>();
        for (int i = 0; i < 20; i++) {
            assertEquals(i, list.size());
            list.add(new SimpleBloomFilter(shape));
            underTest.accept(list);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 100, 2, 1})
    public void testOnMaxSize(int maxSize) {
        Consumer<LinkedList<BloomFilter>> underTest = LayerManager.Cleanup.onMaxSize(maxSize);
        LinkedList<BloomFilter> list = new LinkedList<>();
        for (int i = 0; i < maxSize; i++) {
            assertEquals(i, list.size());
            list.add(new SimpleBloomFilter(shape));
            underTest.accept(list);
        }
        assertEquals(maxSize, list.size());

        for (int i = 0; i < maxSize; i++) {
            list.add(new SimpleBloomFilter(shape));
            underTest.accept(list);
            assertEquals(maxSize, list.size());
        }
    }

    @Test
    public void testOnMaxSizeIllegalValues() {
        assertThrows(IllegalArgumentException.class, () -> LayerManager.Cleanup.onMaxSize(0));
        assertThrows(IllegalArgumentException.class, () -> LayerManager.Cleanup.onMaxSize(-1));
    }

    @Test
    public void testRemoveEmptyTarget() {
        Consumer<LinkedList<BloomFilter>> underTest = LayerManager.Cleanup.removeEmptyTarget();
        LinkedList<BloomFilter> list = new LinkedList<>();

        // removes an empty filter
        BloomFilter bf = new SimpleBloomFilter(shape);
        list.add(bf);
        assertEquals(bf, list.get(0));
        underTest.accept(list);
        assertTrue(list.isEmpty());

        // does not remove a populated filter.
        bf.merge(IndexProducer.fromIndexArray(1));
        list.add(bf);
        assertEquals(bf, list.get(0));
        underTest.accept(list);
        assertEquals(bf, list.get(0));

        // does not remove an empty filter followed by a populated filter.
        list.clear();
        list.add(new SimpleBloomFilter(shape));
        list.add(bf);
        assertEquals(2, list.size());
        underTest.accept(list);
        assertEquals(2, list.size());

        // does not remove multiple empty filters at the end of the list, just the last
        // one.
        list.clear();
        list.add(bf);
        list.add(new SimpleBloomFilter(shape));
        list.add(new SimpleBloomFilter(shape));
        assertEquals(3, list.size());
        underTest.accept(list);
        assertEquals(2, list.size());
        assertEquals(bf, list.get(0));

    }

    @Test
    public void testTarget() {
        boolean[] extendCheckCalled = { false };
        boolean[] cleanupCalled = { false };
        int[] supplierCount = { 0 };
        LayerManager underTest = LayerManager.builder().setSupplier(() -> {
            supplierCount[0]++;
            return new SimpleBloomFilter(shape);
        }).setExtendCheck(lm -> {
            extendCheckCalled[0] = true;
            return true;
        }).setCleanup(ll -> {
            cleanupCalled[0] = true;
        }).build();
        assertFalse(extendCheckCalled[0]);
        assertFalse(cleanupCalled[0]);
        assertEquals(1, supplierCount[0]);
        underTest.getTarget();
        assertTrue(extendCheckCalled[0]);
        assertTrue(cleanupCalled[0]);
        assertEquals(2, supplierCount[0]);
    }

    static class NumberedBloomFilter extends WrappedBloomFilter {
        int value;
        int sequence;
        NumberedBloomFilter(Shape shape, int value, int sequence) {
            super(new SimpleBloomFilter(shape));
            this.value = value;
            this.sequence = sequence;
        }
    }
}
