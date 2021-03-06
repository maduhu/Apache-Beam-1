/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.core;

import static org.apache.beam.sdk.transforms.DoFn.ProcessContinuation.resume;
import static org.apache.beam.sdk.transforms.DoFn.ProcessContinuation.stop;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.Executors;
import javax.annotation.Nullable;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.BigEndianIntegerCoder;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.DoFnTester;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.GlobalWindow;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.util.SideInputReader;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TimestampedValue;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.apache.beam.sdk.values.ValueInSingleWindow;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link SplittableParDo}. */
@RunWith(JUnit4.class)
public class SplittableParDoTest {
  private static final int MAX_OUTPUTS_PER_BUNDLE = 10000;
  private static final Duration MAX_BUNDLE_DURATION = Duration.standardSeconds(5);

  // ----------------- Tests for whether the transform sets boundedness correctly --------------
  private static class SomeRestriction implements Serializable {}

  private static class SomeRestrictionTracker implements RestrictionTracker<SomeRestriction> {
    private final SomeRestriction someRestriction = new SomeRestriction();

    @Override
    public SomeRestriction currentRestriction() {
      return someRestriction;
    }

    @Override
    public SomeRestriction checkpoint() {
      return someRestriction;
    }
  }

  private static class BoundedFakeFn extends DoFn<Integer, String> {
    @ProcessElement
    public void processElement(ProcessContext context, SomeRestrictionTracker tracker) {}

    @GetInitialRestriction
    public SomeRestriction getInitialRestriction(Integer element) {
      return null;
    }

    @NewTracker
    public SomeRestrictionTracker newTracker(SomeRestriction restriction) {
      return null;
    }
  }

  private static class UnboundedFakeFn extends DoFn<Integer, String> {
    @ProcessElement
    public ProcessContinuation processElement(
        ProcessContext context, SomeRestrictionTracker tracker) {
      return stop();
    }

    @GetInitialRestriction
    public SomeRestriction getInitialRestriction(Integer element) {
      return null;
    }

    @NewTracker
    public SomeRestrictionTracker newTracker(SomeRestriction restriction) {
      return null;
    }
  }

  private static PCollection<Integer> makeUnboundedCollection(Pipeline pipeline) {
    return pipeline
        .apply("unbounded", Create.of(1, 2, 3))
        .setIsBoundedInternal(PCollection.IsBounded.UNBOUNDED);
  }

  private static PCollection<Integer> makeBoundedCollection(Pipeline pipeline) {
    return pipeline
        .apply("bounded", Create.of(1, 2, 3))
        .setIsBoundedInternal(PCollection.IsBounded.BOUNDED);
  }

  private static final TupleTag<String> MAIN_OUTPUT_TAG = new TupleTag<String>() {};

  private ParDo.MultiOutput<Integer, String> makeParDo(DoFn<Integer, String> fn) {
    return ParDo.of(fn).withOutputTags(MAIN_OUTPUT_TAG, TupleTagList.empty());
  }

  @Rule
  public TestPipeline pipeline = TestPipeline.create();

  @Test
  public void testBoundednessForBoundedFn() {
    pipeline.enableAbandonedNodeEnforcement(false);

    DoFn<Integer, String> boundedFn = new BoundedFakeFn();
    assertEquals(
        "Applying a bounded SDF to a bounded collection produces a bounded collection",
        PCollection.IsBounded.BOUNDED,
        makeBoundedCollection(pipeline)
            .apply("bounded to bounded", new SplittableParDo<>(makeParDo(boundedFn)))
            .get(MAIN_OUTPUT_TAG)
            .isBounded());
    assertEquals(
        "Applying a bounded SDF to an unbounded collection produces an unbounded collection",
        PCollection.IsBounded.UNBOUNDED,
        makeUnboundedCollection(pipeline)
            .apply("bounded to unbounded", new SplittableParDo<>(makeParDo(boundedFn)))
            .get(MAIN_OUTPUT_TAG)
            .isBounded());
  }

  @Test
  public void testBoundednessForUnboundedFn() {
    pipeline.enableAbandonedNodeEnforcement(false);

    DoFn<Integer, String> unboundedFn = new UnboundedFakeFn();
    assertEquals(
        "Applying an unbounded SDF to a bounded collection produces a bounded collection",
        PCollection.IsBounded.UNBOUNDED,
        makeBoundedCollection(pipeline)
            .apply("unbounded to bounded", new SplittableParDo<>(makeParDo(unboundedFn)))
            .get(MAIN_OUTPUT_TAG)
            .isBounded());
    assertEquals(
        "Applying an unbounded SDF to an unbounded collection produces an unbounded collection",
        PCollection.IsBounded.UNBOUNDED,
        makeUnboundedCollection(pipeline)
            .apply("unbounded to unbounded", new SplittableParDo<>(makeParDo(unboundedFn)))
            .get(MAIN_OUTPUT_TAG)
            .isBounded());
  }

  // ------------------------------- Tests for ProcessFn ---------------------------------

  enum WindowExplosion {
    EXPLODE_WINDOWS,
    DO_NOT_EXPLODE_WINDOWS
  }

  /**
   * A helper for testing {@link SplittableParDo.ProcessFn} on 1 element (but possibly over multiple
   * {@link DoFn.ProcessElement} calls).
   */
  private static class ProcessFnTester<
          InputT, OutputT, RestrictionT, TrackerT extends RestrictionTracker<RestrictionT>>
      implements AutoCloseable {
    private final DoFnTester<
            KeyedWorkItem<String, ElementAndRestriction<InputT, RestrictionT>>, OutputT>
        tester;
    private Instant currentProcessingTime;

    private InMemoryTimerInternals timerInternals;
    private InMemoryStateInternals<String> stateInternals;

    ProcessFnTester(
        Instant currentProcessingTime,
        final DoFn<InputT, OutputT> fn,
        Coder<InputT> inputCoder,
        Coder<RestrictionT> restrictionCoder,
        int maxOutputsPerBundle,
        Duration maxBundleDuration)
        throws Exception {
      final SplittableParDo.ProcessFn<InputT, OutputT, RestrictionT, TrackerT> processFn =
          new SplittableParDo.ProcessFn<>(
              fn, inputCoder, restrictionCoder, IntervalWindow.getCoder());
      this.tester = DoFnTester.of(processFn);
      this.timerInternals = new InMemoryTimerInternals();
      this.stateInternals = InMemoryStateInternals.forKey("dummy");
      processFn.setStateInternalsFactory(
          new StateInternalsFactory<String>() {
            @Override
            public StateInternals<String> stateInternalsForKey(String key) {
              return stateInternals;
            }
          });
      processFn.setTimerInternalsFactory(
          new TimerInternalsFactory<String>() {
            @Override
            public TimerInternals timerInternalsForKey(String key) {
              return timerInternals;
            }
          });
      processFn.setProcessElementInvoker(
          new OutputAndTimeBoundedSplittableProcessElementInvoker<
              InputT, OutputT, RestrictionT, TrackerT>(
              fn,
              tester.getPipelineOptions(),
              new OutputWindowedValueToDoFnTester<>(tester),
              new SideInputReader() {
                @Nullable
                @Override
                public <T> T get(PCollectionView<T> view, BoundedWindow window) {
                  throw new NoSuchElementException();
                }

                @Override
                public <T> boolean contains(PCollectionView<T> view) {
                  return false;
                }

                @Override
                public boolean isEmpty() {
                  return true;
                }
              },
              Executors.newSingleThreadScheduledExecutor(Executors.defaultThreadFactory()),
              maxOutputsPerBundle,
              maxBundleDuration));
      // Do not clone since ProcessFn references non-serializable DoFnTester itself
      // through the state/timer/output callbacks.
      this.tester.setCloningBehavior(DoFnTester.CloningBehavior.DO_NOT_CLONE);
      this.tester.startBundle();
      timerInternals.advanceProcessingTime(currentProcessingTime);

      this.currentProcessingTime = currentProcessingTime;
    }

    @Override
    public void close() throws Exception {
      tester.close();
    }

    /** Performs a seed {@link DoFn.ProcessElement} call feeding the element and restriction. */
    void startElement(InputT element, RestrictionT restriction) throws Exception {
      startElement(
          WindowedValue.of(
              ElementAndRestriction.of(element, restriction),
              currentProcessingTime,
              GlobalWindow.INSTANCE,
              PaneInfo.ON_TIME_AND_ONLY_FIRING),
          WindowExplosion.DO_NOT_EXPLODE_WINDOWS);
    }

    void startElement(
        WindowedValue<ElementAndRestriction<InputT, RestrictionT>> windowedValue,
        WindowExplosion explosion)
        throws Exception {
      switch (explosion) {
        case EXPLODE_WINDOWS:
          tester.processElement(
              KeyedWorkItems.elementsWorkItem("key", windowedValue.explodeWindows()));
          break;
        case DO_NOT_EXPLODE_WINDOWS:
          tester.processElement(
              KeyedWorkItems.elementsWorkItem("key", Arrays.asList(windowedValue)));
          break;
      }
    }

    /**
     * Advances processing time by a given duration and, if any timers fired, performs a non-seed
     * {@link DoFn.ProcessElement} call, feeding it the timers.
     */
    boolean advanceProcessingTimeBy(Duration duration) throws Exception {
      currentProcessingTime = currentProcessingTime.plus(duration);
      timerInternals.advanceProcessingTime(currentProcessingTime);

      List<TimerInternals.TimerData> timers = new ArrayList<>();
      TimerInternals.TimerData nextTimer;
      while ((nextTimer = timerInternals.removeNextProcessingTimer()) != null) {
        timers.add(nextTimer);
      }
      if (timers.isEmpty()) {
        return false;
      }
      tester.processElement(
          KeyedWorkItems.<String, ElementAndRestriction<InputT, RestrictionT>>timersWorkItem(
              "key", timers));
      return true;
    }

    List<TimestampedValue<OutputT>> peekOutputElementsInWindow(BoundedWindow window) {
      return tester.peekOutputElementsInWindow(window);
    }

    List<OutputT> takeOutputElements() {
      return tester.takeOutputElements();
    }

  }

  private static class OutputWindowedValueToDoFnTester<OutputT>
      implements OutputWindowedValue<OutputT> {
    private final DoFnTester<?, OutputT> tester;

    private OutputWindowedValueToDoFnTester(DoFnTester<?, OutputT> tester) {
      this.tester = tester;
    }

    @Override
    public void outputWindowedValue(
        OutputT output,
        Instant timestamp,
        Collection<? extends BoundedWindow> windows,
        PaneInfo pane) {
      sideOutputWindowedValue(tester.getMainOutputTag(), output, timestamp, windows, pane);
    }

    @Override
    public <SideOutputT> void sideOutputWindowedValue(
        TupleTag<SideOutputT> tag,
        SideOutputT output,
        Instant timestamp,
        Collection<? extends BoundedWindow> windows,
        PaneInfo pane) {
      for (BoundedWindow window : windows) {
        tester.getMutableOutput(tag).add(ValueInSingleWindow.of(output, timestamp, window, pane));
      }
    }
  }

  /** A simple splittable {@link DoFn} that's actually monolithic. */
  private static class ToStringFn extends DoFn<Integer, String> {
    @ProcessElement
    public void process(ProcessContext c, SomeRestrictionTracker tracker) {
      c.output(c.element().toString() + "a");
      c.output(c.element().toString() + "b");
      c.output(c.element().toString() + "c");
    }

    @GetInitialRestriction
    public SomeRestriction getInitialRestriction(Integer elem) {
      return new SomeRestriction();
    }

    @NewTracker
    public SomeRestrictionTracker newTracker(SomeRestriction restriction) {
      return new SomeRestrictionTracker();
    }
  }

  @Test
  public void testTrivialProcessFnPropagatesOutputsWindowsAndTimestamp() throws Exception {
    // Tests that ProcessFn correctly propagates windows and timestamp of the element
    // inside the KeyedWorkItem.
    // The underlying DoFn is actually monolithic, so this doesn't test splitting.
    DoFn<Integer, String> fn = new ToStringFn();

    Instant base = Instant.now();

    IntervalWindow w1 =
        new IntervalWindow(
            base.minus(Duration.standardMinutes(1)), base.plus(Duration.standardMinutes(1)));
    IntervalWindow w2 =
        new IntervalWindow(
            base.minus(Duration.standardMinutes(2)), base.plus(Duration.standardMinutes(2)));
    IntervalWindow w3 =
        new IntervalWindow(
            base.minus(Duration.standardMinutes(3)), base.plus(Duration.standardMinutes(3)));

    for (WindowExplosion explosion : WindowExplosion.values()) {
      ProcessFnTester<Integer, String, SomeRestriction, SomeRestrictionTracker> tester =
          new ProcessFnTester<>(
              base, fn, BigEndianIntegerCoder.of(), SerializableCoder.of(SomeRestriction.class),
              MAX_OUTPUTS_PER_BUNDLE, MAX_BUNDLE_DURATION);
      tester.startElement(
          WindowedValue.of(
              ElementAndRestriction.of(42, new SomeRestriction()),
              base,
              Arrays.asList(w1, w2, w3),
              PaneInfo.ON_TIME_AND_ONLY_FIRING),
          explosion);

      for (IntervalWindow w : new IntervalWindow[] {w1, w2, w3}) {
        assertEquals(
            Arrays.asList(
                TimestampedValue.of("42a", base),
                TimestampedValue.of("42b", base),
                TimestampedValue.of("42c", base)),
            tester.peekOutputElementsInWindow(w));
      }
    }
  }

  /** A simple splittable {@link DoFn} that outputs the given element every 5 seconds forever. */
  private static class SelfInitiatedResumeFn extends DoFn<Integer, String> {
    @ProcessElement
    public ProcessContinuation process(ProcessContext c, SomeRestrictionTracker tracker) {
      c.output(c.element().toString());
      return resume().withResumeDelay(Duration.standardSeconds(5)).withWatermark(c.timestamp());
    }

    @GetInitialRestriction
    public SomeRestriction getInitialRestriction(Integer elem) {
      return new SomeRestriction();
    }

    @NewTracker
    public SomeRestrictionTracker newTracker(SomeRestriction restriction) {
      return new SomeRestrictionTracker();
    }
  }

  @Test
  public void testResumeSetsTimer() throws Exception {
    DoFn<Integer, String> fn = new SelfInitiatedResumeFn();
    Instant base = Instant.now();
    ProcessFnTester<Integer, String, SomeRestriction, SomeRestrictionTracker> tester =
        new ProcessFnTester<>(
            base, fn, BigEndianIntegerCoder.of(), SerializableCoder.of(SomeRestriction.class),
            MAX_OUTPUTS_PER_BUNDLE, MAX_BUNDLE_DURATION);

    tester.startElement(42, new SomeRestriction());
    assertThat(tester.takeOutputElements(), contains("42"));

    // Should resume after 5 seconds: advancing by 3 seconds should have no effect.
    assertFalse(tester.advanceProcessingTimeBy(Duration.standardSeconds(3)));
    assertTrue(tester.takeOutputElements().isEmpty());

    // 6 seconds should be enough - should invoke the fn again.
    assertTrue(tester.advanceProcessingTimeBy(Duration.standardSeconds(3)));
    assertThat(tester.takeOutputElements(), contains("42"));

    // Should again resume after 5 seconds: advancing by 3 seconds should again have no effect.
    assertFalse(tester.advanceProcessingTimeBy(Duration.standardSeconds(3)));
    assertTrue(tester.takeOutputElements().isEmpty());

    // 6 seconds should again be enough.
    assertTrue(tester.advanceProcessingTimeBy(Duration.standardSeconds(3)));
    assertThat(tester.takeOutputElements(), contains("42"));
  }

  private static class SomeCheckpoint implements Serializable {
    private int firstUnprocessedIndex;

    private SomeCheckpoint(int firstUnprocessedIndex) {
      this.firstUnprocessedIndex = firstUnprocessedIndex;
    }
  }

  private static class SomeCheckpointTracker implements RestrictionTracker<SomeCheckpoint> {
    private SomeCheckpoint current;
    private boolean isActive = true;

    private SomeCheckpointTracker(SomeCheckpoint current) {
      this.current = current;
    }

    @Override
    public SomeCheckpoint currentRestriction() {
      return current;
    }

    public boolean tryUpdateCheckpoint(int firstUnprocessedIndex) {
      if (!isActive) {
        return false;
      }
      current = new SomeCheckpoint(firstUnprocessedIndex);
      return true;
    }

    @Override
    public SomeCheckpoint checkpoint() {
      isActive = false;
      return current;
    }
  }

  /**
   * A splittable {@link DoFn} that generates the sequence [init, init + total) in batches of given
   * size.
   */
  private static class CounterFn extends DoFn<Integer, String> {
    private final int numTotalOutputs;
    private final int numOutputsPerCall;

    private CounterFn(int numTotalOutputs, int numOutputsPerCall) {
      this.numTotalOutputs = numTotalOutputs;
      this.numOutputsPerCall = numOutputsPerCall;
    }

    @ProcessElement
    public ProcessContinuation process(ProcessContext c, SomeCheckpointTracker tracker) {
      int start = tracker.currentRestriction().firstUnprocessedIndex;
      for (int i = 0; i < numOutputsPerCall; ++i) {
        int index = start + i;
        if (!tracker.tryUpdateCheckpoint(index + 1)) {
          return resume();
        }
        if (index >= numTotalOutputs) {
          return stop();
        }
        c.output(String.valueOf(c.element() + index));
      }
      return resume();
    }

    @GetInitialRestriction
    public SomeCheckpoint getInitialRestriction(Integer elem) {
      throw new UnsupportedOperationException("Expected to be supplied explicitly in this test");
    }

    @NewTracker
    public SomeCheckpointTracker newTracker(SomeCheckpoint restriction) {
      return new SomeCheckpointTracker(restriction);
    }
  }

  @Test
  public void testResumeCarriesOverState() throws Exception {
    DoFn<Integer, String> fn = new CounterFn(3, 1);
    Instant base = Instant.now();
    ProcessFnTester<Integer, String, SomeCheckpoint, SomeCheckpointTracker> tester =
        new ProcessFnTester<>(
            base, fn, BigEndianIntegerCoder.of(), SerializableCoder.of(SomeCheckpoint.class),
            MAX_OUTPUTS_PER_BUNDLE, MAX_BUNDLE_DURATION);

    tester.startElement(42, new SomeCheckpoint(0));
    assertThat(tester.takeOutputElements(), contains("42"));
    assertTrue(tester.advanceProcessingTimeBy(Duration.standardSeconds(1)));
    assertThat(tester.takeOutputElements(), contains("43"));
    assertTrue(tester.advanceProcessingTimeBy(Duration.standardSeconds(1)));
    assertThat(tester.takeOutputElements(), contains("44"));
    assertTrue(tester.advanceProcessingTimeBy(Duration.standardSeconds(1)));
    // After outputting all 3 items, should not output anything more.
    assertEquals(0, tester.takeOutputElements().size());
    // Should also not ask to resume.
    assertFalse(tester.advanceProcessingTimeBy(Duration.standardSeconds(1)));
  }

  @Test
  public void testCheckpointsAfterNumOutputs() throws Exception {
    int max = 100;
    // Create an fn that attempts to 2x output more than checkpointing allows.
    DoFn<Integer, String> fn = new CounterFn(2 * max + max / 2, 2 * max);
    Instant base = Instant.now();
    int baseIndex = 42;

    ProcessFnTester<Integer, String, SomeCheckpoint, SomeCheckpointTracker> tester =
        new ProcessFnTester<>(
            base, fn, BigEndianIntegerCoder.of(), SerializableCoder.of(SomeCheckpoint.class),
            max, MAX_BUNDLE_DURATION);

    List<String> elements;

    tester.startElement(baseIndex, new SomeCheckpoint(0));
    elements = tester.takeOutputElements();
    assertEquals(max, elements.size());
    // Should output the range [0, max)
    assertThat(elements, hasItem(String.valueOf(baseIndex)));
    assertThat(elements, hasItem(String.valueOf(baseIndex + max - 1)));

    assertTrue(tester.advanceProcessingTimeBy(Duration.standardSeconds(1)));
    elements = tester.takeOutputElements();
    assertEquals(max, elements.size());
    // Should output the range [max, 2*max)
    assertThat(elements, hasItem(String.valueOf(baseIndex + max)));
    assertThat(elements, hasItem(String.valueOf(baseIndex + 2 * max - 1)));

    assertTrue(tester.advanceProcessingTimeBy(Duration.standardSeconds(1)));
    elements = tester.takeOutputElements();
    assertEquals(max / 2, elements.size());
    // Should output the range [2*max, 2*max + max/2)
    assertThat(elements, hasItem(String.valueOf(baseIndex + 2 * max)));
    assertThat(elements, hasItem(String.valueOf(baseIndex + 2 * max + max / 2 - 1)));
    assertThat(elements, not(hasItem((String.valueOf(baseIndex + 2 * max + max / 2)))));
  }

  @Test
  public void testCheckpointsAfterDuration() throws Exception {
    // Don't bound number of outputs.
    int max = Integer.MAX_VALUE;
    // But bound bundle duration - the bundle should terminate.
    Duration maxBundleDuration = Duration.standardSeconds(1);
    // Create an fn that attempts to 2x output more than checkpointing allows.
    DoFn<Integer, String> fn = new CounterFn(max, max);
    Instant base = Instant.now();
    int baseIndex = 42;

    ProcessFnTester<Integer, String, SomeCheckpoint, SomeCheckpointTracker> tester =
        new ProcessFnTester<>(
            base, fn, BigEndianIntegerCoder.of(), SerializableCoder.of(SomeCheckpoint.class),
            max, maxBundleDuration);

    List<String> elements;

    tester.startElement(baseIndex, new SomeCheckpoint(0));
    // Bundle should terminate, and should do at least some processing.
    elements = tester.takeOutputElements();
    assertFalse(elements.isEmpty());
    // Bundle should have run for at least the requested duration.
    assertThat(
        Instant.now().getMillis() - base.getMillis(),
        greaterThanOrEqualTo(maxBundleDuration.getMillis()));
  }

  private static class LifecycleVerifyingFn extends DoFn<Integer, String> {
    private enum State {
      BEFORE_SETUP,
      OUTSIDE_BUNDLE,
      INSIDE_BUNDLE,
      TORN_DOWN
    }

    private State state = State.BEFORE_SETUP;

    @ProcessElement
    public void process(ProcessContext c, SomeRestrictionTracker tracker) {
      assertEquals(State.INSIDE_BUNDLE, state);
    }

    @GetInitialRestriction
    public SomeRestriction getInitialRestriction(Integer element) {
      return new SomeRestriction();
    }

    @NewTracker
    public SomeRestrictionTracker newTracker(SomeRestriction restriction) {
      return new SomeRestrictionTracker();
    }

    @Setup
    public void setup() {
      assertEquals(State.BEFORE_SETUP, state);
      state = State.OUTSIDE_BUNDLE;
    }

    @Teardown
    public void tearDown() {
      assertEquals(State.OUTSIDE_BUNDLE, state);
      state = State.TORN_DOWN;
    }

    @StartBundle
    public void startBundle(Context c) {
      assertEquals(State.OUTSIDE_BUNDLE, state);
      state = State.INSIDE_BUNDLE;
    }

    @FinishBundle
    public void finishBundle(Context c) {
      assertEquals(State.INSIDE_BUNDLE, state);
      state = State.OUTSIDE_BUNDLE;
    }
  }

  @Test
  public void testInvokesLifecycleMethods() throws Exception {
    DoFn<Integer, String> fn = new LifecycleVerifyingFn();
    try (ProcessFnTester<Integer, String, SomeRestriction, SomeRestrictionTracker> tester =
        new ProcessFnTester<>(
            Instant.now(),
            fn,
            BigEndianIntegerCoder.of(),
            SerializableCoder.of(SomeRestriction.class),
            MAX_OUTPUTS_PER_BUNDLE,
            MAX_BUNDLE_DURATION)) {
      tester.startElement(42, new SomeRestriction());
    }
  }
}
