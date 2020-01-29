/*
 * Copyright 2004 - 2012 Mirko Nasato and contributors
 *           2016 - 2020 Simon Braconnier and contributors
 *
 * This file is part of JODConverter - Java OpenDocument Converter.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jodconverter.online.office;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.powermock.reflect.Whitebox;

import org.jodconverter.core.document.DefaultDocumentFormatRegistry;
import org.jodconverter.core.office.AbstractOfficeManagerPool;
import org.jodconverter.core.office.InstalledOfficeManagerHolder;
import org.jodconverter.core.office.OfficeException;
import org.jodconverter.core.office.OfficeManager;
import org.jodconverter.core.office.OfficeUtils;
import org.jodconverter.core.office.SimpleOfficeTask;
import org.jodconverter.online.OnlineConverter;

/** Contains tests for the {@link OnlineOfficeManager} class. */
public class OnlineOfficeManagerITest {

  private static final String RESOURCES_PATH = "src/integTest/resources/";
  private static final String SOURCE_FILE_PATH = RESOURCES_PATH + "documents/test1.doc";

  @Test
  public void install_ShouldSetInstalledOfficeManagerHolder() {

    // Ensure we do not replace the current installed manager
    final OfficeManager installedManager = InstalledOfficeManagerHolder.getInstance();
    try {
      final OfficeManager manager = OnlineOfficeManager.install("localhost");
      assertThat(InstalledOfficeManagerHolder.getInstance()).isEqualTo(manager);
    } finally {
      InstalledOfficeManagerHolder.setInstance(installedManager);
    }
  }

  @Test
  public void build_WithDefaultValues_ShouldInitializedOfficeManagerWithDefaultValues() {

    final OfficeManager manager = OnlineOfficeManager.make("localhost");

    assertThat(manager).isInstanceOf(OnlineOfficeManager.class);
    final OnlineOfficeManagerPoolConfig config = Whitebox.getInternalState(manager, "config");
    assertThat(config.getWorkingDir().getPath())
        .isEqualTo(new File(System.getProperty("java.io.tmpdir")).getPath());
    assertThat(config.getTaskExecutionTimeout()).isEqualTo(120_000L);
    assertThat(config.getTaskQueueTimeout()).isEqualTo(30_000L);

    assertThat(manager).extracting("poolSize", "urlConnection").containsExactly(1, "localhost");
  }

  @Test
  public void build_WithCustomValues_ShouldInitializedOfficeManagerWithCustomValues() {

    final OfficeManager manager =
        OnlineOfficeManager.builder()
            .workingDir(System.getProperty("java.io.tmpdir"))
            .poolSize(5)
            .urlConnection("localhost")
            .taskExecutionTimeout(20_000L)
            .taskQueueTimeout(1_000L)
            .build();

    assertThat(manager).isInstanceOf(OnlineOfficeManager.class);
    final OnlineOfficeManagerPoolConfig config = Whitebox.getInternalState(manager, "config");
    assertThat(config.getWorkingDir().getPath())
        .isEqualTo(new File(System.getProperty("java.io.tmpdir")).getPath());
    assertThat(config.getTaskExecutionTimeout()).isEqualTo(20_000L);
    assertThat(config.getTaskQueueTimeout()).isEqualTo(1_000L);

    assertThat(manager).extracting("poolSize", "urlConnection").containsExactly(5, "localhost");
  }

  @Test
  public void build_WithValuesAsString_ShouldInitializedOfficeManagerWithCustomValues() {

    final OfficeManager manager =
        OnlineOfficeManager.builder()
            .urlConnection("localhost")
            .workingDir(new File(System.getProperty("java.io.tmpdir")).getPath())
            .build();

    assertThat(manager).isInstanceOf(AbstractOfficeManagerPool.class);
    final OnlineOfficeManagerPoolConfig config = Whitebox.getInternalState(manager, "config");
    assertThat(config.getWorkingDir().getPath())
        .isEqualTo(new File(System.getProperty("java.io.tmpdir")).getPath());
  }

  @Test
  public void build_WithEmptyValuesAsString_ShouldInitializedOfficeManagerWithDefaultValues() {

    final OfficeManager manager =
        OnlineOfficeManager.builder().urlConnection("localhost").workingDir("   ").build();

    assertThat(manager).isInstanceOf(AbstractOfficeManagerPool.class);
    final OnlineOfficeManagerPoolConfig config = Whitebox.getInternalState(manager, "config");
    assertThat(config.getWorkingDir().getPath())
        .isEqualTo(new File(System.getProperty("java.io.tmpdir")).getPath());
  }

  @Test
  public void build_WithMissingUrlConnection_ThrowIllegalArgumentException() {

    assertThatNullPointerException().isThrownBy(() -> OnlineOfficeManager.builder().build());
  }

  @Test
  public void start_StartTwice_ThrowIllegalStateException() throws OfficeException {

    final OnlineOfficeManager manager = OnlineOfficeManager.make("localhost");
    try {
      manager.start();
      assertThatIllegalStateException().isThrownBy(manager::start);
    } finally {
      manager.stop();
    }
  }

  @Test
  public void start_WhenTerminated_ThrowIllegalStateException() throws OfficeException {

    final OnlineOfficeManager manager = OnlineOfficeManager.make("localhost");
    manager.start();
    manager.stop();
    assertThatIllegalStateException().isThrownBy(manager::start);
  }

  @Test
  public void stop_WhenTerminated_SecondStopIgnored() throws OfficeException {

    final OnlineOfficeManager manager = OnlineOfficeManager.make("localhost");
    manager.start();
    manager.stop();
    assertThatCode(manager::stop).doesNotThrowAnyException();
  }

  @Test
  public void execute_WithoutBeeingStarted_ThrowIllegalStateException() {

    assertThatIllegalStateException()
        .isThrownBy(() -> OnlineOfficeManager.make("localhost").execute(new SimpleOfficeTask()));
  }

  @Test
  public void execute_WhenTerminated_ThrowIllegalStateException() throws OfficeException {

    final OnlineOfficeManager manager = OnlineOfficeManager.make("localhost");
    try {
      manager.start();
    } finally {
      manager.stop();
    }

    assertThatIllegalStateException().isThrownBy(() -> manager.execute(new SimpleOfficeTask()));
  }

  @Test
  public void execute_WithBadUrl_ThrowOfficeException() throws OfficeException {

    final OnlineOfficeManager manager =
        OnlineOfficeManager.builder().urlConnection("url_that_could_not_work").build();
    try {
      manager.start();

      assertThatExceptionOfType(OfficeException.class)
          .isThrownBy(() -> manager.execute(new SimpleOfficeTask()));

    } finally {
      manager.stop();
    }
  }

  @Test
  public void execute_WhenReturnNot200OK_ShouldThrowOfficeException(final @TempDir File testFolder)
      throws OfficeException {

    final File inputFile = new File(SOURCE_FILE_PATH);
    final File outputFile = new File(testFolder, "out.txt");

    final WireMockServer wireMockServer = new WireMockServer(options().port(8000));
    wireMockServer.start();
    try {
      final OfficeManager manager =
          OnlineOfficeManager.builder()
              .urlConnection("http://localhost:8000/lool/convert-to/")
              .build();
      try {
        manager.start();
        wireMockServer.stubFor(
            post(urlPathEqualTo("/lool/convert-to/txt")).willReturn(aResponse().withStatus(400)));

        assertThatExceptionOfType(OfficeException.class)
            .isThrownBy(
                () -> OnlineConverter.make(manager).convert(inputFile).to(outputFile).execute());

      } finally {
        OfficeUtils.stopQuietly(manager);
      }
    } finally {
      wireMockServer.stop();
    }
  }

  @Test
  public void execute_FromFileToFileReturning200OK_TargetShouldContaingExpectedResult(
      final @TempDir File testFolder) throws OfficeException, IOException {

    final File inputFile = new File(SOURCE_FILE_PATH);
    final File outputFile = new File(testFolder, "out.txt");

    final WireMockServer wireMockServer = new WireMockServer(options().port(8000));
    wireMockServer.start();
    try {
      final OfficeManager manager =
          OnlineOfficeManager.builder()
              .urlConnection("http://localhost:8000/lool/convert-to/")
              .build();
      try {
        manager.start();
        wireMockServer.stubFor(
            post(urlPathEqualTo("/lool/convert-to/txt"))
                .willReturn(aResponse().withStatus(200).withBody("Test Document")));

        // Try to converter the input document
        OnlineConverter.make(manager).convert(inputFile).to(outputFile).execute();

        // Check that the output file was created with the expected content.
        final String content = FileUtils.readFileToString(outputFile, StandardCharsets.UTF_8);
        assertThat(content).as("Check content: %s", content).contains("Test Document");
      } finally {
        manager.stop();
      }
    } finally {
      wireMockServer.stop();
    }
  }

  @Test
  public void
      execute_FromInputStreamToOutputStreamReturning200OK_TargetShouldContaingExpectedResult(
          final @TempDir File testFolder) throws OfficeException, IOException {

    final File inputFile = new File(SOURCE_FILE_PATH);
    final File outputFile = new File(testFolder, "out.txt");

    final WireMockServer wireMockServer = new WireMockServer(options().port(8000));
    wireMockServer.start();
    try {
      final OfficeManager manager =
          OnlineOfficeManager.builder()
              .urlConnection("http://localhost:8000/lool/convert-to/")
              .build();
      try {
        manager.start();
        wireMockServer.stubFor(
            post(urlPathEqualTo("/lool/convert-to/txt"))
                .willReturn(aResponse().withStatus(200).withBody("Test Document")));

        // Try to converter the input document
        try (InputStream inputStream = Files.newInputStream(inputFile.toPath());
            OutputStream outputStream = Files.newOutputStream(outputFile.toPath())) {
          OnlineConverter.make(manager)
              .convert(inputStream)
              .as(DefaultDocumentFormatRegistry.DOC)
              .to(outputStream)
              .as(DefaultDocumentFormatRegistry.TXT)
              .execute();
        }

        // Check that the output file was created with the expected content.
        final String content = FileUtils.readFileToString(outputFile, StandardCharsets.UTF_8);
        assertThat(content).as("Check content: %s", content).contains("Test Document");
      } finally {
        manager.stop();
      }
    } finally {
      wireMockServer.stop();
    }
  }
}