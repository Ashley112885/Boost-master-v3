package info.nightscout.androidaps.plugins.aps.EN;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.annotation.Nullable;
import javax.inject.Inject;

import dagger.android.HasAndroidInjector;
import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.IobTotal;
import info.nightscout.androidaps.data.MealData;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.db.TemporaryBasal;
import info.nightscout.androidaps.interfaces.ActivePluginProvider;
import info.nightscout.androidaps.interfaces.InsulinInterface;
import info.nightscout.androidaps.interfaces.ProfileFunction;
import info.nightscout.androidaps.interfaces.PumpInterface;
import info.nightscout.androidaps.logging.AAPSLogger;
import info.nightscout.androidaps.logging.LTag;
import info.nightscout.androidaps.plugins.aps.logger.LoggerCallback;
import info.nightscout.androidaps.plugins.aps.loop.ScriptReader;
import info.nightscout.androidaps.plugins.configBuilder.ConstraintChecker;
import info.nightscout.androidaps.plugins.general.openhumans.OpenHumansUploader;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatus;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.plugins.pump.common.utils.DateTimeUtil;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;
import info.nightscout.androidaps.utils.SafeParse;
import info.nightscout.androidaps.utils.resources.ResourceHelper;
import info.nightscout.androidaps.utils.sharedPreferences.SP;
import info.nightscout.androidaps.utils.stats.TIR;
import info.nightscout.androidaps.utils.stats.TddCalculator;
import info.nightscout.androidaps.utils.DateUtil;
import dagger.android.HasAndroidInjector;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.plugins.bus.RxBusWrapper;
import info.nightscout.androidaps.utils.FabricPrivacy;
import info.nightscout.androidaps.plugins.general.nsclient.NSUpload;
import info.nightscout.androidaps.plugins.general.nsclient.UploadQueue;
import info.nightscout.androidaps.utils.stats.TirCalculator;
import android.util.LongSparseArray;


public class DetermineBasalAdapterENJS {
    private final HasAndroidInjector injector;
    @Inject AAPSLogger aapsLogger;
    @Inject ConstraintChecker constraintChecker;
    @Inject SP sp;
    @Inject ResourceHelper resourceHelper;
    @Inject ProfileFunction profileFunction;
    @Inject TreatmentsPlugin treatmentsPlugin;
    @Inject ActivePluginProvider activePluginProvider;
    @Inject OpenHumansUploader openHumansUploader;
    @Inject DateUtil dateUtil;
    @Inject HasAndroidInjector hasAndroidInjector;
    @Inject MainApp mainApp;
    @Inject RxBusWrapper rxBusWrapper;
    @Inject FabricPrivacy fabricPrivacy;
    @Inject NSUpload nsUpload;
    @Inject UploadQueue uploadQueue;



    private final ScriptReader mScriptReader;
    private JSONObject mProfile;
    private JSONObject mGlucoseStatus;
    private TddCalculator tddAIMI;
    private TirCalculator StatTIR;
    private JSONArray mIobData;
    private JSONObject mMealData;
    private JSONObject mCurrentTemp;
    private JSONObject mAutosensData = null;
    private boolean mMicrobolusAllowed;
    private boolean mSMBAlwaysAllowed;
    private long mCurrentTime;
    private boolean mIsSaveCgmSource;

    private String storedCurrentTemp = null;
    private String storedIobData = null;

    private String storedGlucoseStatus = null;
    private String storedProfile = null;
    private String storedMeal_data = null;

    private String scriptDebug = "";

    /**
     * Main code
     */

    DetermineBasalAdapterENJS(ScriptReader scriptReader, HasAndroidInjector injector) {
        mScriptReader = scriptReader;
        this.injector = injector;
        injector.androidInjector().inject(this);
    }


    @Nullable
    public DetermineBasalResultEN invoke() {


        aapsLogger.debug(LTag.APS, ">>> Invoking detemine_basal <<<");
        aapsLogger.debug(LTag.APS, "Glucose status: " + (storedGlucoseStatus = mGlucoseStatus.toString()));
        aapsLogger.debug(LTag.APS, "IOB data:       " + (storedIobData = mIobData.toString()));
        aapsLogger.debug(LTag.APS, "Current temp:   " + (storedCurrentTemp = mCurrentTemp.toString()));
        aapsLogger.debug(LTag.APS, "Profile:        " + (storedProfile = mProfile.toString()));
        aapsLogger.debug(LTag.APS, "Meal data:      " + (storedMeal_data = mMealData.toString()));
        if (mAutosensData != null)
            aapsLogger.debug(LTag.APS, "Autosens data:  " + mAutosensData.toString());
        else
            aapsLogger.debug(LTag.APS, "Autosens data:  " + "undefined");
        aapsLogger.debug(LTag.APS, "Reservoir data: " + "undefined");
        aapsLogger.debug(LTag.APS, "MicroBolusAllowed:  " + mMicrobolusAllowed);
        aapsLogger.debug(LTag.APS, "SMBAlwaysAllowed:  " + mSMBAlwaysAllowed);
        aapsLogger.debug(LTag.APS, "CurrentTime: " + mCurrentTime);
        aapsLogger.debug(LTag.APS, "isSaveCgmSource: " + mIsSaveCgmSource);


        DetermineBasalResultEN determineBasalResultEN = null;

        Context rhino = Context.enter();
        Scriptable scope = rhino.initStandardObjects();
        // Turn off optimization to make Rhino Android compatible
        rhino.setOptimizationLevel(-1);

        try {

            //register logger callback for console.log and console.error
            ScriptableObject.defineClass(scope, LoggerCallback.class);
            Scriptable myLogger = rhino.newObject(scope, "LoggerCallback", null);
            scope.put("console2", scope, myLogger);
            rhino.evaluateString(scope, readFile("OpenAPSAMA/loggerhelper.js"), "JavaScript", 0, null);

            //set module parent
            rhino.evaluateString(scope, "var module = {\"parent\":Boolean(1)};", "JavaScript", 0, null);
            rhino.evaluateString(scope, "var round_basal = function round_basal(basal, profile) { return basal; };", "JavaScript", 0, null);
            rhino.evaluateString(scope, "require = function() {return round_basal;};", "JavaScript", 0, null);

            //generate functions "determine_basal" and "setTempBasal"
            rhino.evaluateString(scope, readFile("EN/determine-basal.js"), "JavaScript", 0, null);
            rhino.evaluateString(scope, readFile("EN/basal-set-temp.js"), "setTempBasal.js", 0, null);
            Object determineBasalObj = scope.get("determine_basal", scope);
            Object setTempBasalFunctionsObj = scope.get("tempBasalFunctions", scope);

            //call determine-basal
            if (determineBasalObj instanceof Function && setTempBasalFunctionsObj instanceof NativeObject) {
                Function determineBasalJS = (Function) determineBasalObj;

                //prepare parameters
                Object[] params = new Object[]{
                        makeParam(mGlucoseStatus, rhino, scope),
                        makeParam(mCurrentTemp, rhino, scope),
                        makeParamArray(mIobData, rhino, scope),
                        makeParam(mProfile, rhino, scope),
                        makeParam(mAutosensData, rhino, scope),
                        makeParam(mMealData, rhino, scope),
                        setTempBasalFunctionsObj,
                        Boolean.valueOf(mMicrobolusAllowed),
                        makeParam(null, rhino, scope), // reservoir data as undefined
                        Long.valueOf(mCurrentTime),
                        Boolean.valueOf(mIsSaveCgmSource)
                };


                NativeObject jsResult = (NativeObject) determineBasalJS.call(rhino, scope, scope, params);
                scriptDebug = LoggerCallback.getScriptDebug();

                // Parse the jsResult object to a JSON-String
                String result = NativeJSON.stringify(rhino, scope, jsResult, null, null).toString();
                aapsLogger.debug(LTag.APS, "Result: " + result);
                try {
                    JSONObject resultJson = new JSONObject(result);
                    openHumansUploader.enqueueSMBData(mProfile, mGlucoseStatus, mIobData, mMealData, mCurrentTemp, mAutosensData, mMicrobolusAllowed, mSMBAlwaysAllowed, resultJson);
                    determineBasalResultEN = new DetermineBasalResultEN(injector, resultJson);
                } catch (JSONException e) {
                    aapsLogger.error(LTag.APS, "Unhandled exception", e);
                }
            } else {
                aapsLogger.error(LTag.APS, "Problem loading JS Functions");
            }
        } catch (IOException e) {
            aapsLogger.error(LTag.APS, "IOException");
        } catch (RhinoException e) {
            aapsLogger.error(LTag.APS, "RhinoException: (" + e.lineNumber() + "," + e.columnNumber() + ") " + e.toString());
        } catch (IllegalAccessException | InstantiationException | InvocationTargetException e) {
            aapsLogger.error(LTag.APS, e.toString());
        } finally {
            Context.exit();
        }

        storedGlucoseStatus = mGlucoseStatus.toString();
        storedIobData = mIobData.toString();
        storedCurrentTemp = mCurrentTemp.toString();
        storedProfile = mProfile.toString();
        storedMeal_data = mMealData.toString();

        return determineBasalResultEN;

    }

    String getGlucoseStatusParam() {
        return storedGlucoseStatus;
    }

    String getCurrentTempParam() {
        return storedCurrentTemp;
    }

    String getIobDataParam() {
        return storedIobData;
    }

    String getProfileParam() {
        return storedProfile;
    }

    String getMealDataParam() {
        return storedMeal_data;
    }

    String getScriptDebug() {
        return scriptDebug;
    }

    public void setData(Profile profile,
                        double maxIob,
                        double maxBasal,
                        double minBg,
                        double maxBg,
                        double targetBg,
                        double basalrate,
                        IobTotal[] iobArray,
                        GlucoseStatus glucoseStatus,
                        MealData mealData,
                        double autosensDataRatio,
                        boolean tempTargetSet,
                        boolean microBolusAllowed,
                        boolean uamAllowed,
                        boolean advancedFiltering,
                        boolean isSaveCgmSource
    ) throws JSONException {

        advancedFiltering = true; //MD **** FOR DEV HANDSET ONLY ****

        PumpInterface pump = activePluginProvider.getActivePump();
        Double pumpbolusstep = pump.getPumpDescription().bolusStep;

        InsulinInterface insulinInterface = activePluginProvider.getActiveInsulin();
        int insulinPT = insulinInterface.getPeak();

        mProfile = new JSONObject();
        mProfile.put("max_iob", maxIob);
        //mProfile.put("dia", profile.getDia());
        mProfile.put("percentage", profile.getPercentage());
        mProfile.put("type", "current");
        mProfile.put("max_daily_basal", profile.getMaxDailyBasal());
        mProfile.put("max_basal", maxBasal);
        mProfile.put("min_bg", Math.round(minBg));
        mProfile.put("max_bg", Math.round(maxBg));
        mProfile.put("target_bg", Math.round(targetBg));
//        double normal_target_bg = profile.getTargetMgdl(Profile.secondsFromMidnight(mCurrentTime));
        double normal_target_bg = profile.getTargetMgdl();
        mProfile.put("normal_target_bg",Math.round(normal_target_bg));

        mProfile.put("carb_ratio", profile.getIc());
        mProfile.put("sens", profile.getIsfMgdl());
        mProfile.put("max_daily_safety_multiplier", sp.getInt(R.string.key_openapsama_max_daily_safety_multiplier, 3));
        mProfile.put("current_basal_safety_multiplier", sp.getDouble(R.string.key_openapsama_current_basal_safety_multiplier, 4d));
        mProfile.put("high_temptarget_raises_sensitivity", true);
        mProfile.put("low_temptarget_lowers_sensitivity", true);
        mProfile.put("sensitivity_raises_target", sp.getBoolean(R.string.key_sensitivity_raises_target, ENDefaults.sensitivity_raises_target));
        mProfile.put("resistance_lowers_target", sp.getBoolean(R.string.key_resistance_lowers_target, ENDefaults.resistance_lowers_target));
        mProfile.put("adv_target_adjustments", ENDefaults.adv_target_adjustments);
        mProfile.put("exercise_mode", ENDefaults.exercise_mode);
        mProfile.put("half_basal_exercise_target", ENDefaults.half_basal_exercise_target);
        mProfile.put("maxCOB", ENDefaults.maxCOB);
        mProfile.put("skip_neutral_temps", pump.setNeutralTempAtFullHour());
        // min_5m_carbimpact is not used within SMB determinebasal
        //if (mealData.usedMinCarbsImpact > 0) {
        //    mProfile.put("min_5m_carbimpact", mealData.usedMinCarbsImpact);
        //} else {
        //    mProfile.put("min_5m_carbimpact", SP.getDouble(R.string.key_openapsama_min_5m_carbimpact, ENDefaults.min_5m_carbimpact));
        //}
        mProfile.put("remainingCarbsCap", ENDefaults.remainingCarbsCap);
        mProfile.put("enableUAM", uamAllowed);
        mProfile.put("A52_risk_enable", ENDefaults.A52_risk_enable);

        boolean smbEnabled = sp.getBoolean(R.string.key_use_smb, false);
        mProfile.put("SMBInterval", sp.getInt(R.string.key_smbinterval, ENDefaults.SMBInterval));
        mProfile.put("enableSMB_with_COB", smbEnabled && sp.getBoolean(R.string.key_enableSMB_with_COB, false));
        mProfile.put("enableSMB_with_temptarget", smbEnabled && sp.getBoolean(R.string.key_enableSMB_with_temptarget, false));
        mProfile.put("allowSMB_with_high_temptarget", smbEnabled && sp.getBoolean(R.string.key_allowSMB_with_high_temptarget, false));
        mProfile.put("enableSMB_always", smbEnabled && sp.getBoolean(R.string.key_enableSMB_always, false) && advancedFiltering);
        mProfile.put("enableSMB_after_carbs", smbEnabled && sp.getBoolean(R.string.key_enableSMB_after_carbs, false) && advancedFiltering);
        mProfile.put("maxSMBBasalMinutes", sp.getInt(R.string.key_smbmaxminutes, ENDefaults.maxSMBBasalMinutes));
        mProfile.put("maxUAMSMBBasalMinutes", sp.getInt(R.string.key_uamsmbmaxminutes, ENDefaults.maxUAMSMBBasalMinutes));
        //set the min SMB amount to be the amount set by the pump.
        mProfile.put("bolus_increment", pumpbolusstep);
        mProfile.put("carbsReqThreshold", sp.getInt(R.string.key_carbsReqThreshold, ENDefaults.carbsReqThreshold));
        mProfile.put("insulinPeakTime", insulinPT);
        mProfile.put("current_basal", basalrate);
        mProfile.put("temptargetSet", tempTargetSet);
        mProfile.put("autosens_max", SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_autosens_max, "1.2")));
        mProfile.put("autosens_min", SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_autosens_min, "0.7")));
        // mod 7e: can I add use autoisf here?
        mProfile.put("use_autoisf", sp.getBoolean(R.string.key_openapsama_useautoisf, false));
        // mod 7d: can I add autosens_min here?
        mProfile.put("autoisf_max",  SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_autoisf_max, "1.2")));
        mProfile.put("autoisf_hourlychange",  SafeParse.stringToDouble(sp.getString(R.string.key_openapsama_autoisf_hourlychange, "0.2")));

        // patches ==== START
        mProfile.put("enableGhostCOB", sp.getBoolean(R.string.key_use_ghostcob, false));
        mProfile.put("EatingNowIOBMax", sp.getDouble(R.string.key_eatingnow_iobmax, 0.3) * profile.getPercentage()/100);
        mProfile.put("EatingNowTimeStart", sp.getInt(R.string.key_eatingnow_timestart, 9));
        mProfile.put("EatingNowTimeEnd", sp.getInt(R.string.key_eatingnow_timeend, 17));
        mProfile.put("EatingNowinsulinReqPct",SafeParse.stringToDouble(sp.getString(R.string.key_eatingnow_insulinreqpct,"65")));

        mProfile.put("UAMBoost_Bolus_Scale", sp.getDouble(R.string.key_eatingnow_uamboost_bolus_scale, 0));
        mProfile.put("UAMBoost_SMBLimit", sp.getDouble(R.string.key_eatingnow_uamboost_smblimit, 0) * profile.getPercentage()/100);
        mProfile.put("iTimeWindow", sp.getInt(R.string.key_eatingnow_itimeminutes, 15));

        mProfile.put("UAMBoostMAX_Bolus_Scale", sp.getDouble(R.string.key_eatingnow_uamboostmax_bolus_scale, 0));
        mProfile.put("UAMBoostMAX_SMBLimit", sp.getDouble(R.string.key_eatingnow_uamboostmax_smblimit, 0)* profile.getPercentage()/100);
        mProfile.put("iTimeMaxWindow", sp.getInt(R.string.key_eatingnow_itime1minutes, 15));

        mProfile.put("ISFBoost_enabled", sp.getBoolean(R.string.key_eatingnow_use_advanced_isf, false));
        mProfile.put("ISFBoost_SMBLimit", sp.getDouble(R.string.key_eatingnow_isfboost_smblimit, 0));
        mProfile.put("ISF_Max_Scale", sp.getDouble(R.string.key_eatingnow_isf_max_scale, 1));
        mProfile.put("EatingNowBGThreshold", sp.getDouble(R.string.key_eatingnow_bgthreshold, 0));
        mProfile.put("EatingNowPrebolusPct", sp.getDouble(R.string.key_eatingnow_preboluspct, 0.1));
        // patches ==== END



        if (profileFunction.getUnits().equals(Constants.MMOL)) {
            mProfile.put("out_units", "mmol/L");
        }


        long now = System.currentTimeMillis();
        TemporaryBasal tb = treatmentsPlugin.getTempBasalFromHistory(now);

        mCurrentTemp = new JSONObject();
        mCurrentTemp.put("temp", "absolute");
        mCurrentTemp.put("duration", tb != null ? tb.getPlannedRemainingMinutes() : 0);
        mCurrentTemp.put("rate", tb != null ? tb.tempBasalConvertedToAbsolute(now, profile) : 0d);

        // as we have non default temps longer than 30 mintues
        TemporaryBasal tempBasal = treatmentsPlugin.getTempBasalFromHistory(System.currentTimeMillis());
        if (tempBasal != null) {
            mCurrentTemp.put("minutesrunning", tempBasal.getRealDuration());
        }

        mIobData = IobCobCalculatorPlugin.convertToJSONArray(iobArray);

        mGlucoseStatus = new JSONObject();
        mGlucoseStatus.put("glucose", glucoseStatus.glucose);
        mGlucoseStatus.put("noise", glucoseStatus.noise);

        if (sp.getBoolean(R.string.key_always_use_shortavg, false)) {
            mGlucoseStatus.put("delta", glucoseStatus.short_avgdelta);
        } else {
            mGlucoseStatus.put("delta", glucoseStatus.delta);
        }
        mGlucoseStatus.put("short_avgdelta", glucoseStatus.short_avgdelta);
        mGlucoseStatus.put("long_avgdelta", glucoseStatus.long_avgdelta);
        mGlucoseStatus.put("date", glucoseStatus.date);
        // mod 7: append 2 variables for 5% range
        mGlucoseStatus.put("autoISF_duration", glucoseStatus.autoISF_duration);
        mGlucoseStatus.put("autoISF_average", glucoseStatus.autoISF_average);
        mMealData = new JSONObject();
        mMealData.put("carbs", mealData.carbs);
        mMealData.put("boluses", mealData.boluses);
        mMealData.put("mealCOB", mealData.mealCOB);
        mMealData.put("slopeFromMaxDeviation", mealData.slopeFromMaxDeviation);
        mMealData.put("slopeFromMinDeviation", mealData.slopeFromMinDeviation);
        mMealData.put("lastBolusTime", mealData.lastBolusTime);
        mMealData.put("lastCarbTime", mealData.lastCarbTime);
        mMealData.put("lastCarbs", treatmentsPlugin.getLastCarbs());
        mMealData.put("lastBolusCorrTime",treatmentsPlugin.getLastBolusTypeTime(false));
        mMealData.put("lastBolusCorrUnits",treatmentsPlugin.getLastBolusTypeUnits(false));
        mMealData.put("lastSMBTime",treatmentsPlugin.getLastBolusTypeTime(true));
        mMealData.put("lastSMBUnits",treatmentsPlugin.getLastBolusTypeUnits(true));
        mMealData.put("firstBolusCorr",treatmentsPlugin.getFirstENBolusTime(sp.getInt(R.string.key_eatingnow_timestart, 9)));

        tddAIMI = new TddCalculator(hasAndroidInjector,aapsLogger,rxBusWrapper,resourceHelper,mainApp,sp,activePluginProvider,profileFunction,fabricPrivacy,nsUpload,dateUtil,uploadQueue);
        mMealData.put("TDDAIMI1",tddAIMI.averageTDD(tddAIMI.calculate(1)).total);
        mMealData.put("TDDAIMI3",tddAIMI.averageTDD(tddAIMI.calculate(3)).total);
        mMealData.put("TDDAIMI7",tddAIMI.averageTDD(tddAIMI.calculate(7)).total);
        mMealData.put("TDDPUMP",tddAIMI.calculateDaily().total);
        StatTIR = new TirCalculator(resourceHelper,profileFunction,dateUtil);
        int lowMgdl = 80, highMgdl = 144; // 4.4 - 8.0mmol
        mMealData.put("TIR7Above",StatTIR.averageTIR(StatTIR.calculate(7,lowMgdl,highMgdl)).abovePct());
        mMealData.put("TIR7InRange",StatTIR.averageTIR(StatTIR.calculate(7,lowMgdl,highMgdl)).inRangePct());
        mMealData.put("TIR7Below",StatTIR.averageTIR(StatTIR.calculate(7,lowMgdl,highMgdl)).belowPct());
        mMealData.put("TIR3Above",StatTIR.averageTIR(StatTIR.calculate(3,lowMgdl,highMgdl)).abovePct());
        mMealData.put("TIR3InRange",StatTIR.averageTIR(StatTIR.calculate(3,lowMgdl,highMgdl)).inRangePct());
        mMealData.put("TIR3Below",StatTIR.averageTIR(StatTIR.calculate(3,lowMgdl,highMgdl)).belowPct());
        mMealData.put("TIR1Above",StatTIR.averageTIR(StatTIR.calculateDaily(lowMgdl,highMgdl)).abovePct());
        mMealData.put("TIR1InRange",StatTIR.averageTIR(StatTIR.calculateDaily(lowMgdl,highMgdl)).inRangePct());
        mMealData.put("TIR1Below",StatTIR.averageTIR(StatTIR.calculateDaily(lowMgdl,highMgdl)).belowPct());
        // mMealData.put("currentTIR_70_140_Above",StatTIR.averageTIR(StatTIR.calculateDaily(70,140)).abovePct());


        if (constraintChecker.isAutosensModeEnabled().value()) {
            mAutosensData = new JSONObject();
            mAutosensData.put("ratio", autosensDataRatio);
        } else {
            mAutosensData = new JSONObject();
            mAutosensData.put("ratio", 1.0);
        }
        mMicrobolusAllowed = microBolusAllowed;
        mSMBAlwaysAllowed = advancedFiltering;

        mCurrentTime = now;

        mIsSaveCgmSource = isSaveCgmSource;
    }

    private Object makeParam(JSONObject jsonObject, Context rhino, Scriptable scope) {

        if (jsonObject == null) return Undefined.instance;

        return NativeJSON.parse(rhino, scope, jsonObject.toString(), (context, scriptable, scriptable1, objects) -> objects[1]);
    }

    private Object makeParamArray(JSONArray jsonArray, Context rhino, Scriptable scope) {
        //Object param = NativeJSON.parse(rhino, scope, "{myarray: " + jsonArray.toString() + " }", new Callable() {
        return NativeJSON.parse(rhino, scope, jsonArray.toString(), (context, scriptable, scriptable1, objects) -> objects[1]);
    }

    private String readFile(String filename) throws IOException {
        byte[] bytes = mScriptReader.readFile(filename);
        String string = new String(bytes, StandardCharsets.UTF_8);
        if (string.startsWith("#!/usr/bin/env node")) {
            string = string.substring(20);
        }
        return string;
    }

}
