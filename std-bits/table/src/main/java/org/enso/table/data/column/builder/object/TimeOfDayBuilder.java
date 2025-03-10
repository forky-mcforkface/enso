package org.enso.table.data.column.builder.object;

import org.enso.table.data.column.storage.Storage;
import org.enso.table.data.column.storage.TimeOfDayStorage;

import java.time.LocalTime;

/** A builder for string columns. */
public class TimeOfDayBuilder extends TypedBuilderImpl<LocalTime> {
  @Override
  protected LocalTime[] newArray(int size) {
    return new LocalTime[size];
  }

  public TimeOfDayBuilder(int size) {
    super(size);
  }

  @Override
  public int getType() {
    return Storage.Type.TIME_OF_DAY;
  }

  @Override
  public void appendNoGrow(Object o) {
    data[currentSize++] = (LocalTime) o;
  }

  @Override
  public boolean accepts(Object o) {
    return o instanceof LocalTime;
  }

  @Override
  public Storage seal() {
    return new TimeOfDayStorage(data, currentSize);
  }
}
