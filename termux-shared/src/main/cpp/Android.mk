LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_LDLIBS := -llog
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1
LOCAL_CFLAGS += -ffile-prefix-map=$(LOCAL_PATH)=. -fdebug-prefix-map=$(LOCAL_PATH)=. -fdebug-compilation-dir=.
LOCAL_CPPFLAGS += -ffile-prefix-map=$(LOCAL_PATH)=. -fdebug-prefix-map=$(LOCAL_PATH)=. -fdebug-compilation-dir=.
LOCAL_MODULE := local-socket
LOCAL_SRC_FILES := local-socket.cpp
include $(BUILD_SHARED_LIBRARY)
