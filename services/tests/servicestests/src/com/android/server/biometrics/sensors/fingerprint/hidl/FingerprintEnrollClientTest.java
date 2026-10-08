/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.biometrics.sensors.fingerprint.hidl;

import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_HW_UNAVAILABLE;
import static android.hardware.biometrics.BiometricOverlayConstants.REASON_ENROLL_ENROLLING;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.NotificationManager;
import android.hardware.biometrics.fingerprint.V2_1.IBiometricsFingerprint;
import android.hardware.biometrics.fingerprint.V2_1.RequestStatus;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.testing.TestableContext;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.R;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.log.CallbackWithProbe;
import com.android.server.biometrics.log.Probe;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Collections;

@Presubmit
@SmallTest
public class FingerprintEnrollClientTest {

    private static final byte[] HAT = new byte[69];
    private static final int USER_ID = 8;
    private static final int SENSOR_ID = 2;
    private static final long REQUEST_ID = 9;
    private static final int TIMEOUT_SEC = 60;

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null);

    @Mock
    private IBiometricsFingerprint mHal;
    @Mock
    private IBinder mToken;
    @Mock
    private ClientMonitorCallbackConverter mListener;
    @Mock
    private BiometricLogger mBiometricLogger;
    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private BiometricUtils<Fingerprint> mBiometricUtils;
    @Mock
    private IUdfpsOverlayController mUdfpsOverlayController;
    @Mock
    private ClientMonitorCallback mCallback;
    @Mock
    private Probe mLuxProbe;
    @Mock
    private NotificationManager mNotificationManager;

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Before
    public void setup() {
        mContext.addMockSystemService(NotificationManager.class, mNotificationManager);
        when(mContext.getOrCreateTestableResources().getResources()
                .getInteger(R.integer.config_fingerprintMaxTemplatesPerUser)).thenReturn(5);
        when(mBiometricUtils.getBiometricsForUser(mContext, USER_ID))
                .thenReturn(Collections.emptyList());
        when(mBiometricLogger.getAmbientLightProbe(anyBoolean())).thenAnswer(i ->
                new CallbackWithProbe<>(mLuxProbe, i.getArgument(0)));
        when(mBiometricContext.updateContext(any(), anyBoolean())).thenAnswer(
                i -> i.getArgument(0));
    }

    @Test
    public void enrollAccepted_keepsClientRunning() throws RemoteException {
        when(mHal.enroll(any(), eq(USER_ID), eq(TIMEOUT_SEC)))
                .thenReturn(RequestStatus.SYS_OK);
        final FingerprintEnrollClient client = createClient();

        client.start(mCallback);

        verifyEnrollmentStarted();
        verify(mListener, never()).onError(anyInt(), anyInt(), anyInt(), anyInt());
        verify(mCallback, never()).onClientFinished(any(), anyBoolean());
        verify(mUdfpsOverlayController, never()).hideUdfpsOverlay(anyInt());
        verify(mLuxProbe, never()).destroy();
    }

    @Test
    public void enrollRejected_finishesAndHidesOverlay() throws RemoteException {
        when(mHal.enroll(any(), eq(USER_ID), eq(TIMEOUT_SEC)))
                .thenReturn(RequestStatus.SYS_EINVAL);
        final FingerprintEnrollClient client = createClient();

        client.start(mCallback);

        verifyEnrollmentStarted();
        verifyEnrollmentFailed(client);
    }

    @Test
    public void enrollRemoteException_finishesAndHidesOverlay() throws RemoteException {
        when(mHal.enroll(any(), eq(USER_ID), eq(TIMEOUT_SEC)))
                .thenThrow(new RemoteException());
        final FingerprintEnrollClient client = createClient();

        client.start(mCallback);

        verifyEnrollmentStarted();
        verifyEnrollmentFailed(client);
    }

    private void verifyEnrollmentStarted() throws RemoteException {
        verify(mHal).enroll(eq(HAT), eq(USER_ID), eq(TIMEOUT_SEC));
        verify(mUdfpsOverlayController).showUdfpsOverlay(
                eq(REQUEST_ID), eq(SENSOR_ID), eq(REASON_ENROLL_ENROLLING), any());
        verify(mLuxProbe).enable();
    }

    private void verifyEnrollmentFailed(FingerprintEnrollClient client) throws RemoteException {
        verify(mListener).onError(SENSOR_ID, 0 /* cookie */,
                FINGERPRINT_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
        verify(mCallback).onClientFinished(client, false /* success */);
        verify(mUdfpsOverlayController).hideUdfpsOverlay(SENSOR_ID);
        verify(mLuxProbe).destroy();
        verify(mBiometricUtils, never()).addBiometricForUser(any(), anyInt(), any());
    }

    private FingerprintEnrollClient createClient() {
        return new FingerprintEnrollClient(mContext, () -> mHal, mToken, REQUEST_ID,
                mListener, USER_ID, HAT, "owner", mBiometricUtils, TIMEOUT_SEC, SENSOR_ID,
                mBiometricLogger, mBiometricContext, mUdfpsOverlayController,
                null /* sidefpsController */, FingerprintManager.ENROLL_ENROLL);
    }
}
