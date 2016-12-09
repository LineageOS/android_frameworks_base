package com.android.internal.util.arsenic;

import android.os.Environment;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

@SuppressWarnings("AccessOfSystemProperties")
public class CommandResult implements Parcelable {
    private final String TAG = getClass().getSimpleName();
    private final long mStartTime;
    private final int mExitValue;
    private final String mStdout;
    private final String mStderr;
    private final long mEndTime;

    public CommandResult(long startTime,
                         int exitValue,
                         String stdout,
                         String stderr,
                         long endTime) {
        mStartTime = startTime;
        mExitValue = exitValue;
        mStdout = stdout;
        mStderr = stderr;
        mEndTime = endTime;

        Log.d(TAG, "Time to execute: " + (mEndTime - mStartTime) + " ns (nanoseconds)");
        // this is set last so log from here
        checkForErrors();
    }

    // pretty much just forward the constructor from parcelable to our main
    // loading constructor
    @SuppressWarnings("CastToConcreteClass")
    public CommandResult(Parcel inParcel) {
        this(inParcel.readLong(),
                inParcel.readInt(),
                inParcel.readString(),
                inParcel.readString(),
                inParcel.readLong());
    }

    public boolean success() {
        return (mExitValue == 0);
    }

    public long getEndTime() {
        return mEndTime;
    }

    public String getStderr() {
        return new String(mStderr);
    }

    public String getStdout() {
        return new String(mStdout);
    }

    public Integer getExitValue() {
        return mExitValue;
    }

    public long getStartTime() {
        return mStartTime;
    }

    @SuppressWarnings("UnnecessaryExplicitNumericCast")
    private void checkForErrors() {
        if (mExitValue != 0
                || !"".equals(mStderr.trim())) {
            // don't log the commands that failed
            // because the cpu was offline
            boolean skipOfflineCpu =
                    // if core is off locking fails
                    mStderr.contains("chmod: /sys/devices/system/cpu/cpu")
                            // if core is off applying cpu freqs fails
                            || mStderr.contains(": can't create /sys/devices/system/cpu/cpu");
            String lineEnding = System.getProperty("line.separator");
            FileWriter errorWriter = null;
            try {
                File errorLogFile = new File(
                        Environment.getExternalStorageDirectory()
                        + "/aokp/error.txt");
                if (!errorLogFile.exists()) {
                    errorLogFile.createNewFile();
                }
                errorWriter = new FileWriter(errorLogFile, true);
                // only log the cpu state as offline while writing
                if (skipOfflineCpu) {
                    errorWriter.write(lineEnding);
                    errorWriter.write("Attempted to write to an offline cpu core (ignore me).");
                } else {
                    errorWriter.write(TAG + " shell error detected!");
                    errorWriter.write(lineEnding);
                    errorWriter.write("CommandResult {" + this.toString() + '}');
                    errorWriter.write(lineEnding);
                }
                errorWriter.write(lineEnding);
            } catch (IOException e) {
                Log.e(TAG, "Failed to write command result to error file", e);
            } finally {
                if (errorWriter != null) {
                    try {
                        errorWriter.close();
                    } catch (IOException ignored) {
                        // let it go
                    }
                }
            }
        }
    }

    // implement parcelable
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeLong(mStartTime);
        parcel.writeInt(mExitValue);
        parcel.writeString(mStdout);
        parcel.writeString(mStderr);
        parcel.writeLong(mEndTime);
    }

    @Override
    public String toString() {
        return "CommandResult{" +
                ", mStartTime=" + mStartTime +
                ", mExitValue=" + mExitValue +
                ", stdout='" + mStdout + "'" +
                ", stderr='" + mStderr + "'" +
                ", mEndTime=" + mEndTime +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CommandResult)) return false;

        CommandResult that = (CommandResult) o;

        return (mStartTime == that.mStartTime &&
                mExitValue == that.mExitValue &&
                mStdout == that.mStdout &&
                mStderr == that.mStderr &&
                mEndTime == that.mEndTime);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + (int) (mStartTime ^ (mStartTime >>> 32));
        result = 31 * result + mExitValue;
        result = 31 * result + (mStdout != null ? mStdout.hashCode() : 0);
        result = 31 * result + (mStderr != null ? mStderr.hashCode() : 0);
        result = 31 * result + (int) (mEndTime ^ (mEndTime >>> 32));
        return result;
    }
}
