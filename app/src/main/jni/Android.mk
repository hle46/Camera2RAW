MY_LOCAL_PATH := $(call my-dir)

include $(call all-subdir-makefiles)

LOCAL_PATH := $(MY_LOCAL_PATH)

include $(CLEAR_VARS)

LOCAL_MODULE			:= raw
LOCAL_SRC_FILES			:= edu_illinois_sba_camera2raw_Native.cc
LOCAL_LDLIBS    		:= -llog 
LOCAL_CPP_FEATURES 		+= exceptions
LOCAL_CFLAGS            := -DDEBUG -O3 -Wall

include $(BUILD_SHARED_LIBRARY)