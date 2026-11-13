LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-pkg-redirect
LOCAL_SRC_FILES := termux-pkg-redirect.c
LOCAL_LDLIBS := -ldl
include $(BUILD_SHARED_LIBRARY)
