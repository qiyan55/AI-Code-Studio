package com.aicodestudio.ide.ai.models;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.aicodestudio.ide.ai.engine.EngineManager;
import com.aicodestudio.ide.ai.engine.InferenceEngine;
import com.aicodestudio.ide.ai.engine.ModelAdmission;

/**
 * Local Model Manager - 本地量化大模型管理
 * 实现文档: 4.1.2 本地量化大模型调度子模块
 * 
 * 功能：
 * - 导入/删除/预览GGUF/GGML模型
 * - 推理参数配置（上下文长度、温度、最大Token、线程数）
 * - 云端/本地模式切换
 * - 模型加载/卸载
 */
public class LocalModelManager {
    
    private static final String TAG = "LocalModelManager";
    private static final String PREFS = "aicodestudio_models";
    private static LocalModelManager instance;
    
    // 模型格式
    public static final String FORMAT_GGUF = "GGUF";
    public static final String FORMAT_GGML = "GGML";
    
    // 模型状态
    public static final int STATE_NOT_LOADED = 0;
    public static final int STATE_LOADING = 1;
    public static final int STATE_LOADED = 2;
    public static final int STATE_ERROR = 3;
    
    private final Context context;
    private final SharedPreferences prefs;
    private final List<ModelInfo> models;
    private final List<ModelListener> listeners;
    
    private String activeModelId = "";
    private int globalContextLength = 4096;
    private float globalTemperature = 0.7f;
    private int globalMaxTokens = 2048;
    private int globalThreadCount = 4;

    private static final String KEY_ACTIVE_MODEL = "active_model_id";
    
    public static class ModelInfo {
        public String id;
        public String name;
        public String filePath;
        public String format; // GGUF/GGML
        public String quantization; // Q4_0, Q5_1, Q8_0, etc.
        public long fileSize; // bytes
        public int state;
        public String description;
        
        public JSONObject toJSON() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("name", name);
                obj.put("file_path", filePath);
                obj.put("format", format);
                obj.put("quantization", quantization);
                obj.put("file_size", fileSize);
                obj.put("state", state);
                obj.put("description", description);
            } catch (Exception e) {}
            return obj;
        }
        
        public static ModelInfo fromJSON(JSONObject obj) {
            ModelInfo m = new ModelInfo();
            m.id = obj.optString("id");
            m.name = obj.optString("name");
            m.filePath = obj.optString("file_path");
            m.format = obj.optString("format", FORMAT_GGUF);
            m.quantization = obj.optString("quantization", "Q4_0");
            m.fileSize = obj.optLong("file_size", 0);
            m.state = obj.optInt("state", STATE_NOT_LOADED);
            m.description = obj.optString("description", "");
            return m;
        }
        
        public String getSizeReadable() {
            if (fileSize < 1024) return fileSize + " B";
            if (fileSize < 1048576) return String.format("%.1f KB", fileSize / 1024.0);
            if (fileSize < 1073741824) return String.format("%.1f MB", fileSize / 1048576.0);
            return String.format("%.2f GB", fileSize / 1073741824.0);
        }
    }
    
    public interface ModelListener {
        void onModelAdded(ModelInfo model);
        void onModelRemoved(String modelId);
        void onModelLoaded(String modelId);
        void onModelUnloaded(String modelId);
        void onModelStateChanged(String modelId, int state);
    }
    
    private LocalModelManager(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.models = new ArrayList<>();
        this.listeners = new ArrayList<>();
        loadFromStorage();
        loadGlobalParams();
    }
    
    public static synchronized LocalModelManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new LocalModelManager(ctx);
        }
        return instance;
    }
    
    // ===== 4.1.2.1 导入模型 =====
    public ModelInfo importModel(String filePath) {
        File f = new File(filePath);
        if (!f.exists()) {
            Log.e(TAG, "Model file not found: " + filePath);
            return null;
        }
        
        ModelInfo model = new ModelInfo();
        model.id = generateId();
        model.name = f.getName().replace(".gguf", "").replace(".ggml", "");
        model.filePath = filePath;
        model.format = filePath.toLowerCase().endsWith(".gguf") ? FORMAT_GGUF : FORMAT_GGML;
        model.quantization = detectQuantization(f.getName());
        model.fileSize = f.length();
        model.state = STATE_NOT_LOADED;
        model.description = model.format + " | " + model.quantization + " | " + model.getSizeReadable();
        
        models.add(model);
        saveToStorage();
        notifyAdded(model);
        return model;
    }
    
    // ===== 删除模型 =====
    public void deleteModel(String modelId) {
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).id.equals(modelId)) {
                ModelInfo m = models.get(i);
                // 如果已加载，先卸载
                if (m.state == STATE_LOADED) {
                    unloadModel(modelId);
                }
                // 删除文件
                File f = new File(m.filePath);
                if (f.exists()) f.delete();
                
                models.remove(i);
                saveToStorage();
                notifyRemoved(modelId);
                return;
            }
        }
    }
    
    // ===== 加载模型 =====
    /**
     * 加载模型。
     *
     * 走 EngineManager：先做内存准入检查，超预算直接拒绝，
     * 不进入 native 层——避免在内存不足的设备上点加载就崩。
     */
    public void loadModel(final String modelId, final LoadCallback callback) {
        final ModelInfo model = getModelById(modelId);
        if (model == null) {
            callback.onError("模型不存在: " + modelId);
            return;
        }

        final File file = new File(model.filePath);
        if (!file.exists()) {
            model.state = STATE_ERROR;
            notifyStateChanged(modelId, STATE_ERROR);
            callback.onError("模型文件已不存在，请重新导入");
            return;
        }

        model.state = STATE_LOADING;
        notifyStateChanged(modelId, STATE_LOADING);

        final int wantCtx = globalContextLength;

        new Thread(new Runnable() {
            @Override
            public void run() {
                EngineManager.LoadOutcome out =
                        EngineManager.loadWithAdmission(context, file, wantCtx, true);

                if (out.ok) {
                    model.state = STATE_LOADED;
                    activeModelId = modelId;
                    // 持久化 activeModelId，应用重启后可自动加载
                    prefs.edit().putString(KEY_ACTIVE_MODEL, activeModelId).apply();
                    // 记录实际生效的参数，供 UI 展示
                    if (out.options != null) {
                        lastEffectiveCtx = out.options.contextSize;
                        lastEffectiveThreads = out.options.threads;
                        lastDowngraded = out.downgraded;
                    }
                    notifyStateChanged(modelId, STATE_LOADED);
                    notifyLoaded(modelId);
                    postOk(callback, out.message);
                } else if (out.admission != null
                        && out.admission.verdict == ModelAdmission.VERDICT_BLOCK) {
                    // 准入判定为"必崩"，走 onBlocked 通道，把建议一并带上
                    model.state = STATE_ERROR;
                    notifyStateChanged(modelId, STATE_ERROR);
                    postBlocked(callback, out.message, out.advice, out.admission);
                } else {
                    model.state = STATE_ERROR;
                    notifyStateChanged(modelId, STATE_ERROR);
                    postErr(callback, out.message);
                }
            }
        }).start();
    }

    /** 最近一次加载的实际生效参数 */
    public int  lastEffectiveCtx     = 0;
    public int  lastEffectiveThreads = 0;
    public boolean lastDowngraded    = false;

    private void postOk(final LoadCallback cb, final String msg) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                cb.onSuccess();
            }
        });
        Log.i(TAG, "model loaded: " + msg);
    }

    private void postErr(final LoadCallback cb, final String msg) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                cb.onError(msg);
            }
        });
    }

    private void postBlocked(final LoadCallback cb, final String msg,
                             final String advice,
                             final ModelAdmission.AdmissionResult admission) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                if (cb instanceof LoadCallbackEx) {
                    ((LoadCallbackEx) cb).onBlocked(msg, advice, admission);
                } else {
                    cb.onError(msg);
                }
            }
        });
    }

    /**
     * 扩展回调：区分"加载失败"和"准入拦截"。
     * 后者有明确的补救建议，UI 应展示建议而不是单纯报错。
     */
    public interface LoadCallbackEx extends LoadCallback {
        void onBlocked(String message, String advice,
                       ModelAdmission.AdmissionResult admission);
    }

    /** 卸载时同步通知引擎 */
    public void unloadModel(String modelId) {
        ModelInfo model = getModelById(modelId);
        if (model == null) return;

        if (activeModelId.equals(modelId)) {
            try {
                EngineManager.get().unload();
            } catch (Throwable t) {
                Log.w(TAG, "engine unload failed", t);
            }
        }

        model.state = STATE_NOT_LOADED;
        if (activeModelId.equals(modelId)) {
            activeModelId = "";
            // 清除持久化的 activeModelId
            prefs.edit().remove(KEY_ACTIVE_MODEL).apply();
        }
        notifyStateChanged(modelId, STATE_NOT_LOADED);
        notifyUnloaded(modelId);
    }

    /**
     * 后台省电卸载：释放模型内存和 native 资源，
     * 但【保留】"上次使用的模型"记录，回到前台时 autoLoadLastModel() 能自动恢复。
     * 解决应用长期驻留后台时 1.36GB 模型常驻导致的耗电/后台占用。
     */
    public void unloadModelForBattery() {
        if (activeModelId == null || activeModelId.isEmpty()) return;
        ModelInfo model = getModelById(activeModelId);
        if (model == null) return;
        try {
            EngineManager.get().unload();
        } catch (Throwable t) {
            Log.w(TAG, "battery unload failed", t);
        }
        model.state = STATE_NOT_LOADED;
        // 不清除 KEY_ACTIVE_MODEL：保留记录，回前台自动重载
        activeModelId = "";
        notifyStateChanged(model.id, STATE_NOT_LOADED);
        notifyUnloaded(model.id);
        Log.i(TAG, "unloadModelForBattery: " + model.name + " unloaded, record kept");
    }

    /** 引擎是否可用（native 库是否加载） */
    public boolean isEngineAvailable() {
        try {
            return EngineManager.get().available();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前是否已加载模型（用于后台感知：回前台时判断是否需要重新加载） */
    public boolean isModelLoaded() {
        return activeModelId != null && !activeModelId.isEmpty();
    }

    /** 是否有"上次使用的模型"记录（后台卸载时记录被保留，回前台据此自动重载） */
    public boolean hasLastModelRecord() {
        String id = prefs.getString(KEY_ACTIVE_MODEL, "");
        return id != null && !id.isEmpty();
    }

    /** 引擎不可用的原因，可用时返回 null */
    public String engineUnavailableReason() {
        try {
            InferenceEngine e = EngineManager.get();
            return e.available() ? null : e.unavailableReason();
        } catch (Throwable t) {
            return "引擎初始化异常: " + t.getMessage();
        }
    }

    /** 引擎后端信息（实际生效的 SIMD 内核） */
    public String engineBackendInfo() {
        try {
            return EngineManager.get().backendInfo();
        } catch (Throwable t) {
            return "未知";
        }
    }

    /**
     * 重新探测引擎 .so。
     * 用户在 App 内导入引擎后调用，让 EngineManager 重新搜索并加载，
     * 不必重启 App。
     */
    public void refreshEngineState() {
        try {
            EngineManager.bootstrap(context);
        } catch (Throwable t) {
            // 加载失败不改变现有状态，由调用方读取 unavailableReason 展示
        }
    }

    /**
     * 应用启动时自动加载上次使用的模型。
     * 在后台线程中加载，不阻塞应用启动。
     * 覆盖安装 / 进程重启后，模型会自动加载，用户无需手动重新加载。
     */
    public void autoLoadLastModel() {
        // 从 SharedPreferences 读取上次使用的模型 ID
        String lastModelId = prefs.getString(KEY_ACTIVE_MODEL, "");
        if (lastModelId == null || lastModelId.isEmpty()) {
            Log.i(TAG, "autoLoadLastModel: no last model, skip");
            return;
        }

        final ModelInfo model = getModelById(lastModelId);
        if (model == null) {
            Log.w(TAG, "autoLoadLastModel: model not found: " + lastModelId);
            prefs.edit().remove(KEY_ACTIVE_MODEL).apply();
            return;
        }

        // 检查模型文件是否存在
        File file = new File(model.filePath);
        if (!file.exists()) {
            Log.w(TAG, "autoLoadLastModel: model file not found: " + model.filePath);
            return;
        }

        // 检查引擎是否可用
        try {
            if (!EngineManager.get().available()) {
                Log.w(TAG, "autoLoadLastModel: engine not available, skip");
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "autoLoadLastModel: engine check failed", t);
            return;
        }

        Log.i(TAG, "autoLoadLastModel: loading " + model.name + " ...");

        // 在后台线程中加载模型
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    EngineManager.LoadOutcome out =
                            EngineManager.loadWithAdmission(context, file, globalContextLength, true);

                    if (out.ok) {
                        model.state = STATE_LOADED;
                        activeModelId = model.id;
                        if (out.options != null) {
                            lastEffectiveCtx = out.options.contextSize;
                            lastEffectiveThreads = out.options.threads;
                            lastDowngraded = out.downgraded;
                        }
                        // 在主线程通知监听器
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                            @Override public void run() {
                                notifyStateChanged(model.id, STATE_LOADED);
                                notifyLoaded(model.id);
                            }
                        });
                        Log.i(TAG, "autoLoadLastModel: success, " + model.name);
                    } else {
                        Log.w(TAG, "autoLoadLastModel: failed, " + out.message);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "autoLoadLastModel: exception", t);
                }
            }
        }).start();
    }

    // ===== 推理参数配置（文档 4.1.2.4） =====
    public void setContextLength(int length) {
        this.globalContextLength = length;
        saveGlobalParams();
    }
    
    public void setTemperature(float temp) {
        this.globalTemperature = temp;
        saveGlobalParams();
    }
    
    public void setMaxOutputTokens(int tokens) {
        this.globalMaxTokens = tokens;
        saveGlobalParams();
    }
    
    public void setThreadCount(int threads) {
        this.globalThreadCount = threads;
        saveGlobalParams();
    }
    
    // ===== Getters =====
    public List<ModelInfo> getAllModels() {
        return new ArrayList<>(models);
    }
    
    public ModelInfo getActiveModel() {
        return getModelById(activeModelId);
    }
    
    public boolean hasLoadedModel() {
        return !activeModelId.isEmpty();
    }
    
    public int getContextLength() { return globalContextLength; }
    public float getTemperature() { return globalTemperature; }
    public int getMaxTokens() { return globalMaxTokens; }
    public int getThreadCount() { return globalThreadCount; }
    
    // ===== 量化精度检测 =====
    private String detectQuantization(String filename) {
        String lower = filename.toLowerCase();
        String[] types = {"q2_k", "q3_k", "q4_0", "q4_1", "q4_k", "q5_0", "q5_1", "q5_k", "q6_k", "q8_0", "f16", "f32"};
        for (String t : types) {
            if (lower.contains(t)) return t.toUpperCase();
        }
        return "UNKNOWN";
    }
    
    private String generateId() {
        return "model_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 1000);
    }
    
    private ModelInfo getModelById(String id) {
        for (ModelInfo m : models) {
            if (m.id.equals(id)) return m;
        }
        return null;
    }
    
    // ===== 持久化 =====
    private void saveToStorage() {
        JSONArray arr = new JSONArray();
        for (ModelInfo m : models) {
            arr.put(m.toJSON());
        }
        prefs.edit().putString("models_json", arr.toString()).apply();
    }
    
    private void loadFromStorage() {
        String json = prefs.getString("models_json", "[]");
        try {
            JSONArray arr = new JSONArray(json);
            models.clear();
            for (int i = 0; i < arr.length(); i++) {
                ModelInfo m = ModelInfo.fromJSON(arr.getJSONObject(i));
                // 重置状态（重新打开APP时未加载）
                m.state = STATE_NOT_LOADED;
                models.add(m);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load models", e);
        }
    }
    
    private void saveGlobalParams() {
        prefs.edit()
            .putInt("ctx_len", globalContextLength)
            .putFloat("temperature", globalTemperature)
            .putInt("max_tokens", globalMaxTokens)
            .putInt("thread_count", globalThreadCount)
            .apply();
    }
    
    private void loadGlobalParams() {
        globalContextLength = prefs.getInt("ctx_len", 16384);
        globalTemperature = prefs.getFloat("temperature", 0.7f);
        globalMaxTokens = prefs.getInt("max_tokens", 2048);
        globalThreadCount = prefs.getInt("thread_count", 
            Runtime.getRuntime().availableProcessors());
    }
    
    // ===== 监听器 =====
    public void addListener(ModelListener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }
    public void removeListener(ModelListener l) {
        listeners.remove(l);
    }
    private void notifyAdded(ModelInfo m) {
        for (ModelListener l : listeners) l.onModelAdded(m);
    }
    private void notifyRemoved(String id) {
        for (ModelListener l : listeners) l.onModelRemoved(id);
    }
    private void notifyLoaded(String id) {
        for (ModelListener l : listeners) l.onModelLoaded(id);
    }
    private void notifyUnloaded(String id) {
        for (ModelListener l : listeners) l.onModelUnloaded(id);
    }
    private void notifyStateChanged(String id, int state) {
        for (ModelListener l : listeners) l.onModelStateChanged(id, state);
    }
    
    public interface LoadCallback {
        void onSuccess();
        void onError(String msg);
    }
}
