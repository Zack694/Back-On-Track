package com.zack858.backontrack.recording;

public enum RecordingState {
    /** No recording in progress. */
    IDLE,
    /** Currently capturing frames and feeding the encoder. */
    RECORDING,
    /** Encoder is finalising the file in the background. */
    STOPPING
}
