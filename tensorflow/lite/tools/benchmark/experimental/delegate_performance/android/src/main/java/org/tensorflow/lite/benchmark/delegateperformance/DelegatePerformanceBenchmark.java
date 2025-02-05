/* Copyright 2022 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/

package org.tensorflow.lite.benchmark.delegateperformance;

import android.util.Log;
import com.google.flatbuffers.FlatBufferBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import tflite.BenchmarkEvent;
import tflite.TFLiteSettings;
import tflite.proto.benchmark.DelegatePerformance.BenchmarkEventType;
import tflite.proto.benchmark.DelegatePerformance.LatencyResults;

/** Helper class for running delegate performance benchmark. */
class DelegatePerformanceBenchmark {
  private static final String DELEGATE_PERFORMANCE_RESULT_FOLDER = "delegate_performance_result";
  private static final String TAG = "TfLiteBenchmarkHelper";
  private static final String MODEL_EXT = ".tflite";

  static {
    System.loadLibrary("delegate_performance_benchmark");
  }

  public static String createResultFolder(File filesDir, String resultFolder) throws IOException {
    File resultDir = new File(filesDir, DELEGATE_PERFORMANCE_RESULT_FOLDER + "/" + resultFolder);
    String resultPath = resultDir.getAbsolutePath();
    if (resultDir.exists() || resultDir.mkdirs()) {
      Log.i(TAG, "Logging the result to " + resultPath);
      return resultPath;
    }
    throw new IOException("Failed to create directory for " + resultPath);
  }

  /**
   * Extracts the model name from the model file name.
   *
   * <p>Strips out the ".tflite" extension from the input. Returns "model" if the input filename is
   * "model.tflite".
   */
  public static String getModelName(String filename) {
    checkNotNull(filename);
    checkArgument(filename.endsWith(MODEL_EXT));
    return filename.substring(0, filename.length() - MODEL_EXT.length());
  }

  /**
   * Returns a {@code LatencyResults} by parsing the outcome from a TFLite Benchmark Tool execution.
   * If it fails to parse the outcome, this method returns a {@code LatencyResults} with an error
   * event type.
   */
  public static LatencyResults runLatencyBenchmark(
      String[] args,
      TfLiteSettingsListEntry tfliteSettingslistEntry,
      int modelFd,
      long modelOffset,
      long modelSize) {
    byte[] tfliteSettingsByteArray =
        new byte[tfliteSettingslistEntry.tfliteSettings().getByteBuffer().remaining()];
    tfliteSettingslistEntry.tfliteSettings().getByteBuffer().get(tfliteSettingsByteArray);
    tfliteSettingslistEntry.tfliteSettings().getByteBuffer().rewind();

    byte[] latencyResultsByteArray =
        latencyBenchmarkNativeRun(
            args,
            tfliteSettingsByteArray,
            tfliteSettingslistEntry.filePath(),
            modelFd,
            modelOffset,
            modelSize);
    if (latencyResultsByteArray == null || latencyResultsByteArray.length == 0) {
      Log.w(
          TAG,
          String.format(
              "Received null response from native for %s. Treating this as error.",
              tfliteSettingslistEntry.filePath()));
      return LatencyResults.newBuilder()
          .setEventType(BenchmarkEventType.BENCHMARK_EVENT_TYPE_ERROR)
          .build();
    }
    try {
      return LatencyResults.parseFrom(latencyResultsByteArray);
    } catch (IOException e) {
      Log.w(
          TAG,
          String.format(
              "Failed to parse the results running %s with exception %s.",
              tfliteSettingslistEntry.filePath(), e));
      return LatencyResults.newBuilder()
          .setEventType(BenchmarkEventType.BENCHMARK_EVENT_TYPE_ERROR)
          .build();
    }
  }

  /** Returns a {@code BenchmarkEvent} by parsing the outcome from a MiniBenchmark execution. */
  public static BenchmarkEvent runAccuracyBenchmark(
      TfLiteSettingsListEntry tfliteSettingslistEntry,
      int modelFd,
      long modelOffset,
      long modelSize,
      String resultPath) {
    byte[] tfliteSettingsByteArray =
        new byte[tfliteSettingslistEntry.tfliteSettings().getByteBuffer().remaining()];
    tfliteSettingslistEntry.tfliteSettings().getByteBuffer().get(tfliteSettingsByteArray);
    tfliteSettingslistEntry.tfliteSettings().getByteBuffer().rewind();

    byte[] accuracyResultsByteArray =
        accuracyBenchmarkNativeRun(
            tfliteSettingsByteArray, modelFd, modelOffset, modelSize, resultPath);
    ByteBuffer byteBuffer = ByteBuffer.wrap(accuracyResultsByteArray);
    return BenchmarkEvent.getRootAsBenchmarkEvent(byteBuffer);
  }

  /**
   * Loads the input TFLiteSettings JSON files into TfLiteSettingsListEntry instances.
   *
   * <p>If the number of input TFLiteSettings JSON files is 1, we add one default entry at the
   * beginning as reference. The default entry contains a dummy TFLiteSettings structure, which lets
   * the interpreter to apply the default acceleration.
   */
  public static List<TfLiteSettingsListEntry> loadTfLiteSettingsList(String[] jsonFilePaths) {
    List<TfLiteSettingsListEntry> tfliteSettingsList = new ArrayList<>();
    if (jsonFilePaths.length == 1) {
      FlatBufferBuilder tfliteSettingsBuilder = new FlatBufferBuilder();
      TFLiteSettings.startTFLiteSettings(tfliteSettingsBuilder);
      int tfliteSettingsOffset = TFLiteSettings.endTFLiteSettings(tfliteSettingsBuilder);
      tfliteSettingsBuilder.finish(tfliteSettingsOffset);
      tfliteSettingsList.add(
          TfLiteSettingsListEntry.create(
              TFLiteSettings.getRootAsTFLiteSettings(tfliteSettingsBuilder.dataBuffer()),
              "default_delegate",
              /* isTestTarget= */ false));
    }
    for (String jsonFilePath : jsonFilePaths) {
      byte[] tfliteSettingsByteArray = loadTfLiteSettingsJsonNative(jsonFilePath);
      if (tfliteSettingsByteArray == null || tfliteSettingsByteArray.length == 0) {
        Log.e(TAG, "Failed to load TFLiteSetting from JSON file " + jsonFilePath);
        return new ArrayList<>();
      }

      ByteBuffer byteBuffer = ByteBuffer.wrap(tfliteSettingsByteArray);
      tfliteSettingsList.add(
          TfLiteSettingsListEntry.create(
              TFLiteSettings.getRootAsTFLiteSettings(byteBuffer),
              jsonFilePath,
              // TODO(b/250877013): Correct the flag value. This flag is not in use now and will be
              // used when the result aggregation logic is updated.
              /* isTestTarget= */ false));
    }
    return tfliteSettingsList;
  }

  /**
   * Aggregates a list of {@link BenchmarkResultType} into a {@link BenchmarkResultType} based on
   * the requirements. {@code strict} is set to {@code true} when comparing two delegates with the
   * same types or aggregating the results into model-level and session-level. {@code strict} is set
   * to {@code false} when comparing two delegates with different types.
   *
   * <p>If {@code strict} is set to {@code true}, returns:
   *
   * <ul>
   *   <li>PASS if all elements in {@code results} are PASS.
   *   <li>PASS_WITH_WARNING if at least one element in {@code results} is PASS_WITH_WARNING and
   *       {@code results} doesn't contain FAIL.
   *   <li>FAIL if at least one element in {@code results} is FAIL.
   * </ul>
   *
   * Otherwise, returns:
   *
   * <ul>
   *   <li>PASS if all elements in {@code results} are PASS.
   *   <li>PASS_WITH_WARNING if at least one element in {@code results} is PASS_WITH_WARNING or
   *       PASS.
   *   <li>FAIL if all elements in {@code results} are FAIL.
   * </ul>
   */
  public static BenchmarkResultType aggregateResults(
      boolean strict, List<BenchmarkResultType> results) {
    checkState(!results.isEmpty());
    checkState(
        allMatch(
            results,
            EnumSet.of(
                BenchmarkResultType.PASS,
                BenchmarkResultType.PASS_WITH_WARNING,
                BenchmarkResultType.FAIL)));

    if (strict) {
      if (results.contains(BenchmarkResultType.FAIL)) {
        return BenchmarkResultType.FAIL;
      }
      if (results.contains(BenchmarkResultType.PASS_WITH_WARNING)) {
        return BenchmarkResultType.PASS_WITH_WARNING;
      }
      return BenchmarkResultType.PASS;
    } else {
      if (allMatch(results, EnumSet.of(BenchmarkResultType.PASS))) {
        return BenchmarkResultType.PASS;
      }
      if (allMatch(results, EnumSet.of(BenchmarkResultType.FAIL))) {
        return BenchmarkResultType.FAIL;
      }
      return BenchmarkResultType.PASS_WITH_WARNING;
    }
  }

  /**
   * Returns {@code true} when all elements in {@code results} are in the value range specified by
   * {@code targets}.
   */
  private static boolean allMatch(
      List<BenchmarkResultType> results, Set<BenchmarkResultType> targets) {
    for (BenchmarkResultType result : results) {
      if (!targets.contains(result)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Ensures that an object reference passed as a parameter to the calling method is not null.
   *
   * <p>TODO(b/250876587): Consider adding proper annotation support.
   *
   * @param reference an object reference
   * @return the non-null reference that was validated
   * @throws NullPointerException if {@code reference} is null
   */
  public static <T> T checkNotNull(/* @Nullable */ T reference) {
    if (reference == null) {
      throw new NullPointerException();
    }
    return reference;
  }

  /**
   * Ensures the truth of an expression involving one or more parameters to the calling method.
   *
   * @param expression a boolean expression
   * @throws IllegalArgumentException if {@code expression} is false
   */
  public static void checkArgument(boolean expression) {
    if (!expression) {
      throw new IllegalArgumentException();
    }
  }

  /**
   * Ensures the truth of an expression involving the state of the calling instance, but not
   * involving any parameters to the calling method.
   *
   * @param expression a boolean expression
   * @throws IllegalStateException if {@code expression} is false
   */
  public static void checkState(boolean expression) {
    if (!expression) {
      throw new IllegalStateException();
    }
  }

  private static native byte[] latencyBenchmarkNativeRun(
      String[] args,
      byte[] tfliteSettings,
      String tfliteSettingsPath,
      int modelFd,
      long modelOffset,
      long modelSize);

  private static native byte[] accuracyBenchmarkNativeRun(
      byte[] tfliteSettings, int modelFd, long modelOffset, long modelSize, String resultPath);

  private static native byte[] loadTfLiteSettingsJsonNative(String jsonFilePath);
}
