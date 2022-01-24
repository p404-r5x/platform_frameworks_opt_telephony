/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.telephony.metrics;

import static android.telephony.SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER;
import static android.telephony.SubscriptionManager.PHONE_NUMBER_SOURCE_IMS;
import static android.telephony.SubscriptionManager.PHONE_NUMBER_SOURCE_UICC;

import static com.android.internal.telephony.TelephonyStatsLog.CELLULAR_DATA_SERVICE_SWITCH;
import static com.android.internal.telephony.TelephonyStatsLog.CELLULAR_SERVICE_STATE;
import static com.android.internal.telephony.TelephonyStatsLog.PER_SIM_STATUS;
import static com.android.internal.telephony.TelephonyStatsLog.SIM_SLOT_STATE;
import static com.android.internal.telephony.TelephonyStatsLog.SUPPORTED_RADIO_ACCESS_FAMILY;
import static com.android.internal.telephony.TelephonyStatsLog.VOICE_CALL_RAT_USAGE;
import static com.android.internal.telephony.TelephonyStatsLog.VOICE_CALL_SESSION;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.app.StatsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.StatsEvent;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.nano.PersistAtomsProto.CellularDataServiceSwitch;
import com.android.internal.telephony.nano.PersistAtomsProto.CellularServiceState;
import com.android.internal.telephony.nano.PersistAtomsProto.VoiceCallRatUsage;
import com.android.internal.telephony.nano.PersistAtomsProto.VoiceCallSession;
import com.android.internal.telephony.uicc.IccCardStatus.CardState;
import com.android.internal.telephony.uicc.UiccCard;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.uicc.UiccPort;
import com.android.internal.telephony.uicc.UiccSlot;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

public class MetricsCollectorTest extends TelephonyTest {
    private static final StatsManager.PullAtomMetadata POLICY_PULL_DAILY =
            new StatsManager.PullAtomMetadata.Builder()
                    .setCoolDownMillis(24L * 3600L * 1000L)
                    .build();
    private static final long MIN_COOLDOWN_MILLIS = 23L * 3600L * 1000L;
    private static final long MIN_CALLS_PER_BUCKET = 5L;

    // NOTE: these fields are currently 32-bit internally and padded to 64-bit by TelephonyManager
    private static final int SUPPORTED_RAF_1 =
            (int) TelephonyManager.NETWORK_TYPE_BITMASK_GSM
                    | (int) TelephonyManager.NETWORK_TYPE_BITMASK_LTE
                    | (int) TelephonyManager.NETWORK_TYPE_BITMASK_NR;
    private static final int SUPPORTED_RAF_2 =
            (int) TelephonyManager.NETWORK_TYPE_BITMASK_GSM
                    | (int) TelephonyManager.NETWORK_TYPE_BITMASK_1xRTT
                    | (int) TelephonyManager.NETWORK_TYPE_BITMASK_UMTS;
    private static final int SUPPORTED_RAF_BOTH = SUPPORTED_RAF_1 | SUPPORTED_RAF_2;

    // TODO: if we want to check puller registration by mocking StatsManager, we will have to enable
    // inline mocking since the StatsManager class is final

    // b/153195691: we cannot verify the contents of StatsEvent as its getters are marked with @hide

    @Mock private Phone mSecondPhone;
    @Mock private TelephonyStatsLogHelper mTelephonyStatsLog;
    @Mock private UiccSlot mPhysicalSlot;
    @Mock private UiccSlot mEsimSlot;
    @Mock private UiccCard mActiveCard;
    @Mock private UiccPort mActivePort;

    @Mock private ServiceStateStats mServiceStateStats;

    private MetricsCollector mMetricsCollector;

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        mMetricsCollector =
                new MetricsCollector(mContext, mPersistAtomsStorage, mTelephonyStatsLog);
        doReturn(mSST).when(mSecondPhone).getServiceStateTracker();
        doReturn(mServiceStateStats).when(mSST).getServiceStateStats();
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    @SmallTest
    public void onPullAtom_simSlotState_bothSimPresent() {
        // these have been tested extensively in SimSlotStateTest, here we verify atom generation
        doReturn(true).when(mPhysicalSlot).isActive();
        doReturn(CardState.CARDSTATE_PRESENT).when(mPhysicalSlot).getCardState();
        doReturn(false).when(mPhysicalSlot).isEuicc();
        doReturn(true).when(mEsimSlot).isActive();
        doReturn(CardState.CARDSTATE_PRESENT).when(mEsimSlot).getCardState();
        doReturn(true).when(mEsimSlot).isEuicc();
        doReturn(mActiveCard).when(mEsimSlot).getUiccCard();
        doReturn(4).when(mActivePort).getNumApplications();
        doReturn(new UiccPort[] {mActivePort}).when(mActiveCard).getUiccPortList();
        doReturn(new UiccSlot[] {mPhysicalSlot, mEsimSlot}).when(mUiccController).getUiccSlots();
        doReturn(mPhysicalSlot).when(mUiccController).getUiccSlot(eq(0));
        doReturn(mEsimSlot).when(mUiccController).getUiccSlot(eq(1));
        StatsEvent expectedAtom =
                StatsEvent.newBuilder()
                        .setAtomId(SIM_SLOT_STATE)
                        .writeInt(2)
                        .writeInt(2)
                        .writeInt(1)
                        .build();
        List<StatsEvent> actualAtoms = new ArrayList<>();

        int result = mMetricsCollector.onPullAtom(SIM_SLOT_STATE, actualAtoms);

        assertThat(actualAtoms).hasSize(1);
        assertThat(result).isEqualTo(StatsManager.PULL_SUCCESS);
        // TODO(b/153196254): verify atom contents
    }

    @Test
    @SmallTest
    public void onPullAtom_simSlotState_beforeUiccControllerReady() throws Exception {
        // there is a slight chance that MetricsCollector gets pulled after registration while
        // PhoneFactory havne't made UiccController yet, RuntimeException will be thrown
        replaceInstance(UiccController.class, "mInstance", mUiccController, null);
        List<StatsEvent> actualAtoms = new ArrayList<>();

        int result = mMetricsCollector.onPullAtom(SIM_SLOT_STATE, actualAtoms);

        assertThat(actualAtoms).hasSize(0);
        assertThat(result).isEqualTo(StatsManager.PULL_SKIP);
    }

    @Test
    @SmallTest
    public void onPullAtom_supportedRadioAccessFamily_singlePhone() {
        doReturn(SUPPORTED_RAF_1).when(mPhone).getRadioAccessFamily();
        StatsEvent expectedAtom =
                StatsEvent.newBuilder()
                        .setAtomId(SUPPORTED_RADIO_ACCESS_FAMILY)
                        .writeLong(SUPPORTED_RAF_1)
                        .build();
        List<StatsEvent> actualAtoms = new ArrayList<>();

        int result = mMetricsCollector.onPullAtom(SUPPORTED_RADIO_ACCESS_FAMILY, actualAtoms);

        assertThat(actualAtoms).hasSize(1);
        assertThat(result).isEqualTo(StatsManager.PULL_SUCCESS);
        // TODO(b/153196254): verify atom contents
    }

    @Test
    @SmallTest
    public void onPullAtom_supportedRadioAccessFamily_dualPhones() {
        doReturn(SUPPORTED_RAF_1).when(mPhone).getRadioAccessFamily();
        doReturn(SUPPORTED_RAF_2).when(mSecondPhone).getRadioAccessFamily();
        mPhones = new Phone[] {mPhone, mSecondPhone};
        StatsEvent expectedAtom =
                StatsEvent.newBuilder()
                        .setAtomId(SUPPORTED_RADIO_ACCESS_FAMILY)
                        .writeLong(SUPPORTED_RAF_BOTH)
                        .build();
        List<StatsEvent> actualAtoms = new ArrayList<>();

        int result = mMetricsCollector.onPullAtom(SUPPORTED_RADIO_ACCESS_FAMILY, actualAtoms);

        assertThat(actualAtoms).hasSize(1);
        assertThat(result).isEqualTo(StatsManager.PULL_SUCCESS);
        // TODO(b/153196254): verify atom contents
    }

    @Test
    @SmallTest
    public void onPullAtom_supportedRadioAccessFamily_dualPhonesWithUnknownRaf() {
        doReturn(SUPPORTED_RAF_1).when(mPhone).getRadioAccessFamily();
        doReturn((int) TelephonyManager.NETWORK_TYPE_BITMASK_UNKNOWN)
                .when(mSecondPhone)
                .getRadioAccessFamily();
        mPhones = new Phone[] {mPhone, mSecondPhone};
        StatsEvent expectedAtom =
                StatsEvent.newBuilder()
                        .setAtomId(SUPPORTED_RADIO_ACCESS_FAMILY)
                        .writeLong(SUPPORTED_RAF_1)
                        .build();
        List<StatsEvent> actualAtoms = new ArrayList<>();

        int result = mMetricsCollector.onPullAtom(SUPPORTED_RADIO_ACCESS_FAMILY, actualAtoms);

        assertThat(actualAtoms).hasSize(1);
        assertThat(result).isEqualTo(StatsManager.PULL_SUCCESS);
        // TODO(b/153196254): verify atom contents
    }

    @Test
    @SmallTest
    public void onPullAtom_supportedRadioAccessFamily_beforePhoneReady() throws Exception {
        replaceInstance(PhoneFactory.class, "sMadeDefaults", true, false);
        List<StatsEvent> actualAtoms = new ArrayList<>();

        int result = mMetricsCollector.onPullAtom(SUPPORTED_RADIO_ACCESS_FAMILY, actualAtoms);

        assertThat(actualAtoms).hasSize(0);
        assertThat(result).isEqualTo(StatsManager.PULL_SKIP);
    }

    @Test
    @SmallTest
    public void onPullAtom_voiceCallRatUsage_empty() throws Exception {
        doReturn(new VoiceCallRatUsage[0])
                .when(mPersistAtomsStorage)
                .getVoiceCallRatUsages(anyLong());
        List<StatsEvent> actualAtoms = new ArrayList<>();

        int result = mMetricsCollector.onPullAtom(VOICE_CALL_RAT_USAGE, actualAtoms);

        assertThat(actualAtoms).hasSize(0);
        assertThat(result).isEqualTo(StatsManager.PULL_SUCCESS);
    }

    @Test
    @SmallTest
    public void onPullAtom_voiceCallRatUsage_tooFrequent() throws Exception {
        doReturn(null).when(mPersistAtomsStorage).getVoiceCallRatUsages(anyLong());
        List<StatsEvent> actualAtoms = new ArrayList<>();

        int result = mMetricsCollector.onPullAtom(VOICE_CALL_RAT_USAGE, actualAtoms);

        assertThat(actualAtoms).hasSize(0);
        assertThat(result).isEqualTo(StatsManager.PULL_SKIP);
        verify(mPersistAtomsStorage, times(1)).getVoiceCallRatUsages(eq(MIN_COOLDOWN_MILLIS));
        verifyNoMoreInteractions(mPersistAtomsStorage);
    }

    @Test
    @SmallTest
    public void onPullAtom_voiceCallRatUsage_bucketWithTooFewCalls() throws Exception {
        VoiceCallRatUsage usage1 = new VoiceCallRatUsage();
        usage1.callCount = MIN_CALLS_PER_BUCKET;
        VoiceCallRatUsage usage2 = new VoiceCallRatUsage();
        usage2.callCount = MIN_CALLS_PER_BUCKET - 1L;
        doReturn(new VoiceCallRatUsage[] {usage1, usage1, usage1, usage2})
                .when(mPersistAtomsStorage)
                .getVoiceCallRatUsages(anyLong());
        List<StatsEvent> actualAtoms = new ArrayList<>();

        int result = mMetricsCollector.onPullAtom(VOICE_CALL_RAT_USAGE, actualAtoms);

        assertThat(actualAtoms).hasSize(3); // usage 2 should be dropped
        assertThat(result).isEqualTo(StatsManager.PULL_SUCCESS);
        // TODO(b/153196254): verify atom contents
    }

    @Test
    @SmallTest
    public void onPullAtom_voiceCallSession_empty() throws Exception {
        doReturn(new VoiceCallSession[0])
                .when(mPersistAtomsStorage)
                .getVoiceCallSessions(anyLong());
        List<StatsEvent> actualAtoms = new ArrayList<>();

        int result = mMetricsCollector.onPullAtom(VOICE_CALL_SESSION, actualAtoms);

        assertThat(actualAtoms).hasSize(0);
        assertThat(result).isEqualTo(StatsManager.PULL_SUCCESS);
    }

    @Test
    @SmallTest
    public void onPullAtom_voiceCallSession_tooFrequent() throws Exception {
        doReturn(null).when(mPersistAtomsStorage).getVoiceCallSessions(anyLong());
        List<StatsEvent> actualAtoms = new ArrayList<>();

        int result = mMetricsCollector.onPullAtom(VOICE_CALL_SESSION, actualAtoms);

        assertThat(actualAtoms).hasSize(0);
        assertThat(result).isEqualTo(StatsManager.PULL_SKIP);
        verify(mPersistAtomsStorage, times(1)).getVoiceCallSessions(eq(MIN_COOLDOWN_MILLIS));
        verifyNoMoreInteractions(mPersistAtomsStorage);
    }

    @Test
    @SmallTest
    public void onPullAtom_voiceCallSession_multipleCalls() throws Exception {
        VoiceCallSession call = new VoiceCallSession();
        doReturn(new VoiceCallSession[] {call, call, call, call})
                .when(mPersistAtomsStorage)
                .getVoiceCallSessions(anyLong());
        List<StatsEvent> actualAtoms = new ArrayList<>();

        int result = mMetricsCollector.onPullAtom(VOICE_CALL_SESSION, actualAtoms);

        assertThat(actualAtoms).hasSize(4);
        assertThat(result).isEqualTo(StatsManager.PULL_SUCCESS);
        // TODO(b/153196254): verify atom contents
    }

    @Test
    @SmallTest
    public void onPullAtom_cellularDataServiceSwitch_empty() throws Exception {
        doReturn(new CellularDataServiceSwitch[0])
                .when(mPersistAtomsStorage)
                .getCellularDataServiceSwitches(anyLong());
        List<StatsEvent> actualAtoms = new ArrayList<>();

        int result = mMetricsCollector.onPullAtom(CELLULAR_DATA_SERVICE_SWITCH, actualAtoms);

        assertThat(actualAtoms).hasSize(0);
        assertThat(result).isEqualTo(StatsManager.PULL_SUCCESS);
        // TODO(b/153196254): verify atom contents
    }

    @Test
    @SmallTest
    public void onPullAtom_cellularDataServiceSwitch_tooFrequent() throws Exception {
        doReturn(null).when(mPersistAtomsStorage).getCellularDataServiceSwitches(anyLong());
        List<StatsEvent> actualAtoms = new ArrayList<>();

        int result = mMetricsCollector.onPullAtom(CELLULAR_DATA_SERVICE_SWITCH, actualAtoms);

        assertThat(actualAtoms).hasSize(0);
        assertThat(result).isEqualTo(StatsManager.PULL_SKIP);
        verify(mPersistAtomsStorage, times(1))
                .getCellularDataServiceSwitches(eq(MIN_COOLDOWN_MILLIS));
        verifyNoMoreInteractions(mPersistAtomsStorage);
    }

    @Test
    @SmallTest
    public void onPullAtom_cellularDataServiceSwitch_multipleSwitches() throws Exception {
        CellularDataServiceSwitch serviceSwitch = new CellularDataServiceSwitch();
        doReturn(new CellularDataServiceSwitch[] {serviceSwitch, serviceSwitch, serviceSwitch})
                .when(mPersistAtomsStorage)
                .getCellularDataServiceSwitches(anyLong());
        List<StatsEvent> actualAtoms = new ArrayList<>();

        int result = mMetricsCollector.onPullAtom(CELLULAR_DATA_SERVICE_SWITCH, actualAtoms);

        assertThat(actualAtoms).hasSize(3);
        assertThat(result).isEqualTo(StatsManager.PULL_SUCCESS);
        // TODO(b/153196254): verify atom contents
    }

    @Test
    @SmallTest
    public void onPullAtom_cellularServiceState_empty() throws Exception {
        doReturn(new CellularServiceState[0])
                .when(mPersistAtomsStorage)
                .getCellularServiceStates(anyLong());
        List<StatsEvent> actualAtoms = new ArrayList<>();

        int result = mMetricsCollector.onPullAtom(CELLULAR_SERVICE_STATE, actualAtoms);

        assertThat(actualAtoms).hasSize(0);
        assertThat(result).isEqualTo(StatsManager.PULL_SUCCESS);
        // TODO(b/153196254): verify atom contents
    }

    @Test
    @SmallTest
    public void onPullAtom_cellularServiceState_tooFrequent() throws Exception {
        doReturn(null).when(mPersistAtomsStorage).getCellularServiceStates(anyLong());
        List<StatsEvent> actualAtoms = new ArrayList<>();

        int result = mMetricsCollector.onPullAtom(CELLULAR_SERVICE_STATE, actualAtoms);

        assertThat(actualAtoms).hasSize(0);
        assertThat(result).isEqualTo(StatsManager.PULL_SKIP);
        verify(mPersistAtomsStorage, times(1)).getCellularServiceStates(eq(MIN_COOLDOWN_MILLIS));
        verifyNoMoreInteractions(mPersistAtomsStorage);
    }

    @Test
    @SmallTest
    public void onPullAtom_cellularServiceState_multipleStates() throws Exception {
        CellularServiceState state = new CellularServiceState();
        doReturn(new CellularServiceState[] {state, state, state})
                .when(mPersistAtomsStorage)
                .getCellularServiceStates(anyLong());
        List<StatsEvent> actualAtoms = new ArrayList<>();

        int result = mMetricsCollector.onPullAtom(CELLULAR_SERVICE_STATE, actualAtoms);

        assertThat(actualAtoms).hasSize(3);
        assertThat(result).isEqualTo(StatsManager.PULL_SUCCESS);
        // TODO(b/153196254): verify atom contents
    }

    @Test
    @SmallTest
    public void onPullAtom_perSimStatus() throws Exception {
        // Make PhoneFactory.getPhones() return an array of two
        replaceInstance(PhoneFactory.class, "sPhones", null, new Phone[] {mPhone, mSecondPhone});
        // phone 0 setup
        doReturn(0).when(mPhone).getPhoneId();
        doReturn(1).when(mPhone).getSubId();
        doReturn(100).when(mPhone).getCarrierId();
        doReturn("6506953210")
                .when(mSubscriptionController)
                .getPhoneNumber(1, PHONE_NUMBER_SOURCE_UICC);
        doReturn("")
                .when(mSubscriptionController)
                .getPhoneNumber(1, PHONE_NUMBER_SOURCE_CARRIER);
        doReturn("+16506953210")
                .when(mSubscriptionController)
                .getPhoneNumber(1, PHONE_NUMBER_SOURCE_IMS);
        SubscriptionInfo subscriptionInfo1 = mock(SubscriptionInfo.class);
        doReturn("us").when(subscriptionInfo1).getCountryIso();
        doReturn(subscriptionInfo1).when(mSubscriptionController).getSubscriptionInfo(1);
        // phone 1 setup
        doReturn(1).when(mSecondPhone).getPhoneId();
        doReturn(2).when(mSecondPhone).getSubId();
        doReturn(101).when(mSecondPhone).getCarrierId();
        doReturn("0123")
                .when(mSubscriptionController)
                .getPhoneNumber(2, PHONE_NUMBER_SOURCE_UICC);
        doReturn("16506950123")
                .when(mSubscriptionController)
                .getPhoneNumber(2, PHONE_NUMBER_SOURCE_CARRIER);
        doReturn("+16506950123")
                .when(mSubscriptionController)
                .getPhoneNumber(2, PHONE_NUMBER_SOURCE_IMS);
        SubscriptionInfo subscriptionInfo2 = mock(SubscriptionInfo.class);
        doReturn("us").when(subscriptionInfo2).getCountryIso();
        doReturn(subscriptionInfo2).when(mSubscriptionController).getSubscriptionInfo(2);
        List<StatsEvent> actualAtoms = new ArrayList<>();

        int result = mMetricsCollector.onPullAtom(PER_SIM_STATUS, actualAtoms);

        verify(mTelephonyStatsLog).buildStatsEvent(
                PER_SIM_STATUS, 0, 100, 1, 0, 1);
        verify(mTelephonyStatsLog).buildStatsEvent(
                PER_SIM_STATUS, 1, 101, 1, 2, 2);
        assertThat(actualAtoms).hasSize(2);
        assertThat(result).isEqualTo(StatsManager.PULL_SUCCESS);
    }

    @Test
    @SmallTest
    public void onPullAtom_perSimStatus_noSubscriptionController_skip() throws Exception {
        // Make SubscriptionController.getInstance() return null
        replaceInstance(SubscriptionController.class, "sInstance", null, null);
        List<StatsEvent> actualAtoms = new ArrayList<>();

        int result = mMetricsCollector.onPullAtom(PER_SIM_STATUS, actualAtoms);

        assertThat(actualAtoms).isEmpty();
        assertThat(result).isEqualTo(StatsManager.PULL_SKIP);
    }
}
