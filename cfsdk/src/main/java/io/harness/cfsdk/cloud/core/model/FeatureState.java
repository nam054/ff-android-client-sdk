/*
 * Harness feature flag service client apis
 * No description provided (generated by Openapi Generator https://github.com/openapitools/openapi-generator)
 *
 * The version of the OpenAPI document: 1.0.0
 * Contact: cf@harness.io
 *
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */


package io.harness.cfsdk.cloud.core.model;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

/**
 * Gets or Sets FeatureState
 */
@JsonAdapter(FeatureState.Adapter.class)
public enum FeatureState {
  
  ON("on"),
  
  OFF("off");

  private String value;

  FeatureState(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  @Override
  public String toString() {
    return String.valueOf(value);
  }

  public static FeatureState fromValue(String value) {
    for (FeatureState b : FeatureState.values()) {
      if (b.value.equals(value)) {
        return b;
      }
    }
    throw new IllegalArgumentException("Unexpected value '" + value + "'");
  }

  public static class Adapter extends TypeAdapter<FeatureState> {
    @Override
    public void write(final JsonWriter jsonWriter, final FeatureState enumeration) throws IOException {
      jsonWriter.value(enumeration.getValue());
    }

    @Override
    public FeatureState read(final JsonReader jsonReader) throws IOException {
      String value = jsonReader.nextString();
      return FeatureState.fromValue(value);
    }
  }
}

