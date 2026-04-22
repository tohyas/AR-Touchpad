#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/uinput.h>
#include <linux/input.h>
#include <string.h>
#include <errno.h>
#include <sys/time.h>
#include <android/log.h>

#define TAG  "UinputJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static int g_fd = -1;

extern "C" {

JNIEXPORT jint JNICALL
Java_com_pgratz_artouchpad_UinputNative_nOpen(JNIEnv*, jclass) {
    g_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (g_fd < 0) LOGE("open /dev/uinput failed errno=%d", errno);
    else          LOGI("opened /dev/uinput fd=%d", g_fd);
    return g_fd;
}

JNIEXPORT jint JNICALL
Java_com_pgratz_artouchpad_UinputNative_nIoctl(JNIEnv*, jclass, jint request, jint value) {
    int ret = ioctl(g_fd, (unsigned long)request, (int)value);
    if (ret < 0) LOGE("ioctl(0x%x, %d) failed errno=%d", request, value, errno);
    return ret;
}

JNIEXPORT jint JNICALL
Java_com_pgratz_artouchpad_UinputNative_nWriteDevInfo(JNIEnv* env, jclass, jstring jname) {
    struct uinput_user_dev dev;
    memset(&dev, 0, sizeof(dev));
    const char* name = env->GetStringUTFChars(jname, nullptr);
    strncpy(dev.name, name, UINPUT_MAX_NAME_SIZE - 1);
    env->ReleaseStringUTFChars(jname, name);
    dev.id.bustype = BUS_USB;
    dev.id.vendor  = 0x1234;
    dev.id.product = 0x5678;
    dev.id.version = 1;
    ssize_t n = write(g_fd, &dev, sizeof(dev));
    if (n < 0) LOGE("write dev info failed errno=%d", errno);
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_com_pgratz_artouchpad_UinputNative_nWriteEvent(JNIEnv*, jclass, jint type, jint code, jint value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    ev.time    = tv;
    ev.type    = (uint16_t)type;
    ev.code    = (uint16_t)code;
    ev.value   = (int32_t)value;
    ssize_t n  = write(g_fd, &ev, sizeof(ev));
    if (n < 0) LOGE("writeEvent(%d,%d,%d) failed errno=%d", type, code, value, errno);
    return (jint)n;
}

JNIEXPORT void JNICALL
Java_com_pgratz_artouchpad_UinputNative_nClose(JNIEnv*, jclass) {
    if (g_fd >= 0) {
        ioctl(g_fd, UI_DEV_DESTROY);
        close(g_fd);
        g_fd = -1;
        LOGI("uinput device destroyed");
    }
}

} // extern "C"
