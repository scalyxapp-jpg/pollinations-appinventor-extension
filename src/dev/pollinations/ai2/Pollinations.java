package dev.pollinations.ai2;

import com.google.appinventor.components.annotations.DesignerComponent;
import com.google.appinventor.components.annotations.DesignerProperty;
import com.google.appinventor.components.annotations.PropertyCategory;
import com.google.appinventor.components.annotations.SimpleEvent;
import com.google.appinventor.components.annotations.SimpleFunction;
import com.google.appinventor.components.annotations.SimpleObject;
import com.google.appinventor.components.annotations.SimpleProperty;
import com.google.appinventor.components.annotations.UsesPermissions;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.common.PropertyTypeConstants;
import com.google.appinventor.components.runtime.AndroidNonvisibleComponent;
import com.google.appinventor.components.runtime.ComponentContainer;
import com.google.appinventor.components.runtime.EventDispatcher;
import com.google.appinventor.components.runtime.util.AsynchUtil;

import java.io.File;
import java.util.List;

/**
 * Pollinations for MIT App Inventor and Kodular.
 *
 * <p>Text, images, speech-to-text and questions about a picked image, plus a
 * device-flow sign-in so no API key has to be shipped inside the app.
 */
@DesignerComponent(version = Pollinations.VERSION,
        versionName = "1.0",
        description = "Generate text and images, transcribe a recording, and ask about a "
                + "picture, using the user's own Pollinations account. Includes a sign-in "
                + "flow so no API key ships inside the app.",
        category = ComponentCategory.EXTENSION,
        nonVisible = true,
        iconName = "")
@SimpleObject(external = true)
@UsesPermissions(permissionNames = "android.permission.INTERNET")
public class Pollinations extends AndroidNonvisibleComponent {

    public static final int VERSION = 1;

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are a helpful assistant inside a mobile app. Answer briefly and plainly.";

    private String apiKey = "";
    private String textModel = PollinationsApi.DEFAULT_TEXT_MODEL;
    private String imageModel = PollinationsApi.DEFAULT_IMAGE_MODEL;
    private String systemPrompt = DEFAULT_SYSTEM_PROMPT;
    private int imageSize = 512;

    public Pollinations(ComponentContainer container) {
        super(container.$form());
    }

    /** Work that runs off the UI thread. */
    private interface Job {
        void run() throws PollinationsApi.PollinationsException;
    }

    private PollinationsApi client() {
        PollinationsApi api = new PollinationsApi(apiKey);
        return api;
    }

    /**
     * Runs a job in the background and reports failures through GotError.
     * Every network call has to leave the UI thread, or an app freezes.
     */
    private void background(final Job job) {
        AsynchUtil.runAsynchronously(new Runnable() {
            @Override
            public void run() {
                try {
                    job.run();
                } catch (PollinationsApi.PollinationsException error) {
                    reportError(error.getMessage());
                } catch (RuntimeException error) {
                    reportError(String.valueOf(error.getMessage()));
                }
            }
        });
    }

    private void onUiThread(final Runnable work) {
        form.runOnUiThread(work);
    }

    private void reportError(final String message) {
        onUiThread(new Runnable() {
            @Override
            public void run() {
                GotError(message == null ? "Something went wrong" : message);
            }
        });
    }

    // ------------------------------------------------------------ properties

    @SimpleProperty(category = PropertyCategory.BEHAVIOR,
            description = "Your Pollinations API key. Leave empty and use StartSignIn instead.")
    public String ApiKey() {
        return apiKey;
    }

    @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING, defaultValue = "")
    @SimpleProperty
    public void ApiKey(String key) {
        this.apiKey = key == null ? "" : key.trim();
    }

    @SimpleProperty(category = PropertyCategory.BEHAVIOR,
            description = "Text model for Ask, AskAboutImage and ListModels. See ListModels.")
    public String TextModel() {
        return textModel;
    }

    @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING,
            defaultValue = PollinationsApi.DEFAULT_TEXT_MODEL)
    @SimpleProperty
    public void TextModel(String model) {
        this.textModel = model == null ? PollinationsApi.DEFAULT_TEXT_MODEL : model.trim();
    }

    @SimpleProperty(category = PropertyCategory.BEHAVIOR,
            description = "Image model for GenerateImageUrl and DownloadImage.")
    public String ImageModel() {
        return imageModel;
    }

    @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING,
            defaultValue = PollinationsApi.DEFAULT_IMAGE_MODEL)
    @SimpleProperty
    public void ImageModel(String model) {
        this.imageModel = model == null ? PollinationsApi.DEFAULT_IMAGE_MODEL : model.trim();
    }

    @SimpleProperty(category = PropertyCategory.BEHAVIOR,
            description = "Square image size in pixels. The API needs at least 256.")
    public int ImageSize() {
        return imageSize;
    }

    @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_NON_NEGATIVE_INTEGER, defaultValue = "512")
    @SimpleProperty
    public void ImageSize(int size) {
        this.imageSize = PollinationsApi.clampSide(size);
    }

    @SimpleProperty(category = PropertyCategory.BEHAVIOR,
            description = "Instructions that shape Ask. Change it to give the app a role.")
    public String SystemPrompt() {
        return systemPrompt;
    }

    @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING,
            defaultValue = DEFAULT_SYSTEM_PROMPT)
    @SimpleProperty
    public void SystemPrompt(String prompt) {
        this.systemPrompt = prompt == null || prompt.trim().isEmpty() ? DEFAULT_SYSTEM_PROMPT : prompt;
    }

    // --------------------------------------------------------------- blocks

    @SimpleFunction(description = "Asks the text model and raises GotText with the answer.")
    public void Ask(final String prompt) {
        final String model = textModel;
        final String instructions = systemPrompt;
        background(new Job() {
            @Override
            public void run() throws PollinationsApi.PollinationsException {
                final String answer = client().ask(instructions, prompt, model);
                onUiThread(new Runnable() {
                    @Override
                    public void run() {
                        GotText(answer);
                    }
                });
            }
        });
    }

    @SimpleFunction(description = "Renders an image and raises GotImageUrl with a link to it.")
    public void GenerateImageUrl(final String prompt) {
        final String model = imageModel;
        final int size = imageSize;
        background(new Job() {
            @Override
            public void run() throws PollinationsApi.PollinationsException {
                final String url = client().imageUrl(prompt, model, size);
                onUiThread(new Runnable() {
                    @Override
                    public void run() {
                        GotImageUrl(url);
                    }
                });
            }
        });
    }

    @SimpleFunction(description = "Renders an image, saves it in the app's files, and raises "
            + "GotImageFile with the path, for use with an Image component.")
    public void DownloadImage(final String prompt, final String fileName) {
        final String model = imageModel;
        final int size = imageSize;
        final File target = new File(form.getFilesDir(), fileName);
        background(new Job() {
            @Override
            public void run() throws PollinationsApi.PollinationsException {
                final String path = client().downloadImage(prompt, model, size, target, null);
                onUiThread(new Runnable() {
                    @Override
                    public void run() {
                        GotImageFile(path);
                    }
                });
            }
        });
    }

    @SimpleFunction(description = "Transcribes a recording (for example a SoundRecorder file) "
            + "and raises GotText with the words. The Web component cannot do this.")
    public void TranscribeRecording(final String filePath) {
        final String model = "openai/whisper-1";
        background(new Job() {
            @Override
            public void run() throws PollinationsApi.PollinationsException {
                final String text = client().transcribe(new File(filePath), model);
                onUiThread(new Runnable() {
                    @Override
                    public void run() {
                        GotText(text);
                    }
                });
            }
        });
    }

    @SimpleFunction(description = "Asks the text model about a local picture (from the Camera, "
            + "ImagePicker or a file) and raises GotText with the answer.")
    public void AskAboutImage(final String imagePath, final String question) {
        final String model = textModel;
        background(new Job() {
            @Override
            public void run() throws PollinationsApi.PollinationsException {
                final String answer = client().askAboutImage(new File(imagePath), question, model);
                onUiThread(new Runnable() {
                    @Override
                    public void run() {
                        GotText(answer);
                    }
                });
            }
        });
    }

    @SimpleFunction(description = "Lists the live model ids. Set imageModels to true for the "
            + "image models. Raises GotModels with the ids joined by commas.")
    public void ListModels(final boolean imageModels) {
        background(new Job() {
            @Override
            public void run() throws PollinationsApi.PollinationsException {
                List<String> names = client().models(imageModels);
                StringBuilder joined = new StringBuilder();
                for (int index = 0; index < names.size(); index++) {
                    if (index > 0) {
                        joined.append(",");
                    }
                    joined.append(names.get(index));
                }
                final String text = joined.toString();
                onUiThread(new Runnable() {
                    @Override
                    public void run() {
                        GotModels(text);
                    }
                });
            }
        });
    }

    @SimpleFunction(description = "Starts a sign-in so the user connects their own Pollinations "
            + "account. Raises GotSignInCode, then SignedIn once they approve it.")
    public void StartSignIn() {
        background(new Job() {
            @Override
            public void run() throws PollinationsApi.PollinationsException {
                final PollinationsApi api = client();
                final PollinationsApi.DeviceCode code = api.requestDeviceCode();
                onUiThread(new Runnable() {
                    @Override
                    public void run() {
                        GotSignInCode(code.userCode, code.verificationUrl);
                    }
                });

                // The device code is valid for 30 minutes; keep checking until then.
                long deadline = System.currentTimeMillis() + 30L * 60L * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    String minted = api.pollDeviceCode(code);
                    if (minted != null) {
                        // Java 7: an inner class needs a final local.
                        final String key = minted;
                        apiKey = key;
                        onUiThread(new Runnable() {
                            @Override
                            public void run() {
                                SignedIn(key);
                            }
                        });
                        return;
                    }
                    try {
                        Thread.sleep(Math.max(2, code.intervalSeconds) * 1000L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                reportError("The sign-in was not approved in time. Try again for a new code.");
            }
        });
    }

    // --------------------------------------------------------------- events

    @SimpleEvent(description = "The answer to Ask, TranscribeRecording or AskAboutImage.")
    public void GotText(String text) {
        EventDispatcher.dispatchEvent(this, "GotText", text);
    }

    @SimpleEvent(description = "A link to the generated image.")
    public void GotImageUrl(String url) {
        EventDispatcher.dispatchEvent(this, "GotImageUrl", url);
    }

    @SimpleEvent(description = "The path of the downloaded image, for an Image component.")
    public void GotImageFile(String path) {
        EventDispatcher.dispatchEvent(this, "GotImageFile", path);
    }

    @SimpleEvent(description = "The model ids, joined by commas.")
    public void GotModels(String models) {
        EventDispatcher.dispatchEvent(this, "GotModels", models);
    }

    @SimpleEvent(description = "Show this code to the user together with the sign-in link.")
    public void GotSignInCode(String code, String verificationUrl) {
        EventDispatcher.dispatchEvent(this, "GotSignInCode", code, verificationUrl);
    }

    @SimpleEvent(description = "The user approved the sign-in. The ApiKey is now set.")
    public void SignedIn(String apiKey) {
        EventDispatcher.dispatchEvent(this, "SignedIn", apiKey);
    }

    @SimpleEvent(description = "Something went wrong. The message is meant for the user.")
    public void GotError(String message) {
        EventDispatcher.dispatchEvent(this, "GotError", message);
    }
}
