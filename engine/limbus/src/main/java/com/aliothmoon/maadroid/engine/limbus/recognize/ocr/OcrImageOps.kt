package com.aliothmoon.maadroid.engine.limbus.recognize.ocr

import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** LALC OCR 的掩码与预处理；与模板匹配的裁剪语义不同。 */
internal object OcrImageOps {
    /** 保留整帧坐标和字体大小，仅将指定区域外涂黑。返回值由调用方释放。 */
    fun maskedFrame(screenBgr: Mat, region: Rect): Mat {
        val masked = Mat.zeros(screenBgr.rows(), screenBgr.cols(), screenBgr.type())
        try {
            val source = Mat(screenBgr, region)
            try {
                val target = Mat(masked, region)
                try { source.copyTo(target) } finally { target.release() }
            } finally { source.release() }
            return masked
        } catch (failure: Throwable) {
            masked.release()
            throw failure
        }
    }

    /** rapid_ocr.py 先转灰度，再做 CLAHE(clipLimit=2, tileGridSize=16×16)。 */
    fun prepare(screenBgr: Mat): Mat {
        val gray = Mat()
        val enhanced = Mat()
        val prepared = Mat()
        val clahe = Imgproc.createCLAHE(2.0, Size(16.0, 16.0))
        try {
            if (screenBgr.channels() == 1) screenBgr.copyTo(gray)
            else Imgproc.cvtColor(screenBgr, gray, Imgproc.COLOR_BGR2GRAY)
            clahe.apply(gray, enhanced)
            // RapidOCR 将灰度输入扩成三通道；检测与识别使用同一份增强后的图。
            Imgproc.cvtColor(enhanced, prepared, Imgproc.COLOR_GRAY2BGR)
            return prepared
        } catch (failure: Throwable) {
            prepared.release()
            throw failure
        } finally {
            gray.release()
            enhanced.release()
            clahe.collectGarbage()
            clahe.clear()
        }
    }
}
