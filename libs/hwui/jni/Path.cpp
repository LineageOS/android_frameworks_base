/* libs/android_runtime/android/graphics/Path.cpp
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#include "Path.h"

#include <map>
#include <mutex>
#include <vector>

#include "GraphicsJNI.h"
#include "SkGeometry.h"  // WARNING: Internal Skia Header
#include "SkPath.h"
#include "SkPathBuilder.h"
#include "SkPathIter.h"
#include "SkPathOps.h"

namespace android {

// Holds both an SkPathBuilder and an (optional/cached) SkPath, to present
// a unified interface with legacy path editing semantics.
//
// Ths SkPath is snapped lazily, when needed, and cached for reuse. Editing
// operations relocate the path data to the builder, and discard/invalidate
// the cached SkPath.
//
// This behavior ensures that at any point in time the path data is not stored
// twice, and is also well aligned with regular Path client patterns where
// construction and use are cleanly sequenced (build-then-use, without further
// modifications).
class PathWrapper {
public:
    PathWrapper() = default;
    explicit PathWrapper(const SkPath& path) : mPath(path), mState(State::kPath) {}

    PathWrapper& operator=(const SkPath& path) {
        mBuilder = SkPathBuilder();
        mPath = path;
        mState.store(State::kPath, std::memory_order_relaxed);
        return *this;
    }

    // All const methods require a path for const thread safety.
    bool isEmpty() const { return this->ensurePath().isEmpty(); }
    SkPathFillType getFillType() const { return this->ensurePath().getFillType(); }
    SkPathIter iter() const { return this->ensurePath().iter(); }
    SkSpan<const SkPathVerb> verbs() const { return this->ensurePath().verbs(); }
    SkSpan<const SkPoint> points() const { return this->ensurePath().points(); }
    const SkRect& getBounds() const { return this->ensurePath().getBounds(); }
    bool isRect(SkRect* rect) const { return this->ensurePath().isRect(rect); }
    bool isConvex() const { return this->ensurePath().isConvex(); }
    uint32_t getGenerationID() const { return this->ensurePath().getGenerationID(); }
    bool isInterpolatable(const PathWrapper& other) const {
        return this->ensurePath().isInterpolatable(other.getPath());
    }
    bool interpolate(const PathWrapper& ending, float weight, PathWrapper* result) const {
        return this->ensurePath().interpolate(ending.getPath(), weight, &result->getPath());
    }

    // These are supported in either form.
    void setFillType(SkPathFillType ft) {
        if (mState.load(std::memory_order_relaxed) == State::kPath) {
            mPath.setFillType(ft);
        } else {
            mBuilder.setFillType(ft);
        }
    }
    void offset(float dx, float dy) {
        if (mState.load(std::memory_order_relaxed) == State::kPath) {
            mPath = mPath.makeOffset(dx, dy);
        } else {
            mBuilder.offset(dx, dy);
        }
    }
    void transform(const SkMatrix& matrix, PathWrapper* dst = nullptr) {
        if (dst && dst != this) {
            // When dst is specified, it receives the result.
            *dst = this->ensurePath().makeTransform(matrix);
        } else {
            // Otherwise, transform in place.
            if (mState.load(std::memory_order_relaxed) == State::kPath) {
                mPath = mPath.makeTransform(matrix);
            } else {
                mBuilder.transform(matrix);
            }
        }
    }

    // Editing functions require a builder.
    void incReserve(int extraPtCount) { this->ensureBuilder().incReserve(extraPtCount); }
    void moveTo(float x, float y) { this->ensureBuilder().moveTo({x, y}); }
    void rMoveTo(float x, float y) { this->ensureBuilder().rMoveTo({x, y}); }
    void lineTo(float x, float y) { this->ensureBuilder().lineTo({x, y}); }
    void rLineTo(float x, float y) { this->ensureBuilder().rLineTo({x, y}); }
    void quadTo(float x1, float y1, float x2, float y2) {
        this->ensureBuilder().quadTo({x1, y1}, {x2, y2});
    }
    void rQuadTo(float x1, float y1, float x2, float y2) {
        this->ensureBuilder().rQuadTo({x1, y1}, {x2, y2});
    }
    void conicTo(float x1, float y1, float x2, float y2, float w) {
        this->ensureBuilder().conicTo({x1, y1}, {x2, y2}, w);
    }
    void rConicTo(float x1, float y1, float x2, float y2, float w) {
        this->ensureBuilder().rConicTo({x1, y1}, {x2, y2}, w);
    }
    void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        this->ensureBuilder().cubicTo({x1, y1}, {x2, y2}, {x3, y3});
    }
    void rCubicTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        this->ensureBuilder().rCubicTo({x1, y1}, {x2, y2}, {x3, y3});
    }
    void arcTo(const SkRect& oval, float startAngle, float sweepAngle, bool forceMoveTo) {
        this->ensureBuilder().arcTo(oval, startAngle, sweepAngle, forceMoveTo);
    }
    void close() { this->ensureBuilder().close(); }

    void addRect(const SkRect& rect, SkPathDirection dir) {
        this->ensureBuilder().addRect(rect, dir);
    }
    void addOval(const SkRect& oval, SkPathDirection dir) {
        this->ensureBuilder().addOval(oval, dir);
    }
    void addCircle(float x, float y, float r, SkPathDirection dir) {
        this->ensureBuilder().addCircle(x, y, r, dir);
    }
    void addArc(const SkRect& oval, float startAngle, float sweepAngle) {
        this->ensureBuilder().addArc(oval, startAngle, sweepAngle);
    }
    void addRoundRect(const SkRRect& rrect, SkPathDirection dir) {
        this->ensureBuilder().addRRect(rrect, dir);
    }
    void addPath(const PathWrapper& path, const SkMatrix& m) {
        SkPath src = path.getPath();
        this->ensureBuilder().addPath(src, m);
    }
    void setLastPt(float x, float y) { this->ensureBuilder().setLastPt(x, y); }

    void reset() {
        mBuilder = SkPathBuilder();
        mPath.reset();
    }
    void rewind() {
        mBuilder.reset();
        mPath.reset();
    }

    SkPath& getPath() const { return this->ensurePath(); }
    SkPathBuilder& getBuilder() const { return this->ensureBuilder(); }

private:
    PathWrapper(const PathWrapper&) = delete;
    PathWrapper& operator=(const PathWrapper&) = delete;

    // The current path data is either in the builder (mutable state), or in the SkPath snapshot
    // (immutable state).  We transition between states lazily, as required by the API entry point
    // (const -> immutable vs non-const -> mutable).  The optimal sequence, involving a single
    // transition, is a finite mutable phase (path construction) followed by const-only operations
    // (immutable path use).
    enum class State {
        kBuilder,
        kPath,
    };

    SkPath& ensurePath() const {
        // The builder -> path transition is synchronized, to preserve thread safety for const
        // public methods.  Using DCLP to minimize locking overhead.
        if (mState.load(std::memory_order_acquire) != State::kPath) {
            std::lock_guard lg(mStateMutex);
            if (mState.load(std::memory_order_relaxed) != State::kPath) {
                mPath = mBuilder.detach();
                // detach() clears the builder but (at the moment) does not deallocate its storage.
                mBuilder = SkPathBuilder();
                mState.store(State::kPath, std::memory_order_release);
            }
        }
        return mPath;
    }

    SkPathBuilder& ensureBuilder() const {
        // The path -> builder transition is not synchronized since non-const operations are
        // inherently racy, and there is no point in pretending to be thread-safe at this level.
        if (mState.load(std::memory_order_relaxed) != State::kBuilder) {
            mBuilder = mPath;
            mPath.reset();
            mState.store(State::kBuilder, std::memory_order_relaxed);
        }
        return mBuilder;
    }

    mutable SkPathBuilder mBuilder;
    mutable SkPath mPath;
    mutable std::atomic<State> mState = State::kBuilder;
    mutable std::mutex mStateMutex;
};

static PathWrapper* AsPathWrapper(jlong objHandle) {
    return reinterpret_cast<PathWrapper*>(objHandle);
}

SkPath* AsSkPath(jlong objHandle) {
    return objHandle ? &AsPathWrapper(objHandle)->getPath() : nullptr;
}

SkPathBuilder* AsSkPathBuilder(jlong objHandle) {
    return objHandle ? &AsPathWrapper(objHandle)->getBuilder() : nullptr;
}

class SkPathGlue {
public:
    static void finalizer(PathWrapper* obj) { delete obj; }

    // ---------------- Regular JNI -----------------------------

    static jlong init(JNIEnv* env, jclass clazz) {
        return reinterpret_cast<jlong>(new PathWrapper());
    }

    static jlong init_Path(JNIEnv* env, jclass clazz, jlong valHandle) {
        const auto* val = AsPathWrapper(valHandle);
        // Resolving to a path before initialization ensures
        // that path data is initially shared between instances.
        return reinterpret_cast<jlong>(new PathWrapper(val->getPath()));
    }

    static jlong getFinalizer(JNIEnv* env, jclass clazz) {
        return static_cast<jlong>(reinterpret_cast<uintptr_t>(&finalizer));
    }

    static void set(JNIEnv* env, jclass clazz, jlong dstHandle, jlong srcHandle) {
        auto* dst = AsPathWrapper(dstHandle);
        const auto* src = AsPathWrapper(srcHandle);
        // Resolving to a path before assignment ensures
        // that path data is initially shared between instances.
        *dst = src->getPath();
    }

    static void computeBounds(JNIEnv* env, jclass clazz, jlong objHandle, jobject jbounds) {
        const SkRect& bounds = AsPathWrapper(objHandle)->getBounds();
        GraphicsJNI::rect_to_jrectf(bounds, env, jbounds);
    }

    static void incReserve(JNIEnv* env, jclass clazz, jlong objHandle, jint extraPtCount) {
        AsPathWrapper(objHandle)->incReserve(extraPtCount);
    }

    static void moveTo__FF(JNIEnv* env, jclass clazz, jlong objHandle, jfloat x, jfloat y) {
        AsPathWrapper(objHandle)->moveTo(x, y);
    }

    static void rMoveTo(JNIEnv* env, jclass clazz, jlong objHandle, jfloat dx, jfloat dy) {
        AsPathWrapper(objHandle)->rMoveTo(dx, dy);
    }

    static void lineTo__FF(JNIEnv* env, jclass clazz, jlong objHandle, jfloat x, jfloat y) {
        AsPathWrapper(objHandle)->lineTo(x, y);
    }

    static void rLineTo(JNIEnv* env, jclass clazz, jlong objHandle, jfloat dx, jfloat dy) {
        AsPathWrapper(objHandle)->rLineTo(dx, dy);
    }

    static void quadTo__FFFF(JNIEnv* env, jclass clazz, jlong objHandle, jfloat x1, jfloat y1,
            jfloat x2, jfloat y2) {
        AsPathWrapper(objHandle)->quadTo(x1, y1, x2, y2);
    }

    static void rQuadTo(JNIEnv* env, jclass clazz, jlong objHandle, jfloat dx1, jfloat dy1,
            jfloat dx2, jfloat dy2) {
        AsPathWrapper(objHandle)->rQuadTo(dx1, dy1, dx2, dy2);
    }

    static void conicTo(JNIEnv* env, jclass clazz, jlong objHandle, jfloat x1, jfloat y1, jfloat x2,
                        jfloat y2, jfloat weight) {
        AsPathWrapper(objHandle)->conicTo(x1, y1, x2, y2, weight);
    }

    static void rConicTo(JNIEnv* env, jclass clazz, jlong objHandle, jfloat dx1, jfloat dy1,
                         jfloat dx2, jfloat dy2, jfloat weight) {
        AsPathWrapper(objHandle)->rConicTo(dx1, dy1, dx2, dy2, weight);
    }

    static void cubicTo__FFFFFF(JNIEnv* env, jclass clazz, jlong objHandle, jfloat x1, jfloat y1,
            jfloat x2, jfloat y2, jfloat x3, jfloat y3) {
        AsPathWrapper(objHandle)->cubicTo(x1, y1, x2, y2, x3, y3);
    }

    static void rCubicTo(JNIEnv* env, jclass clazz, jlong objHandle, jfloat x1, jfloat y1,
            jfloat x2, jfloat y2, jfloat x3, jfloat y3) {
        AsPathWrapper(objHandle)->rCubicTo(x1, y1, x2, y2, x3, y3);
    }

    static void arcTo(JNIEnv* env, jclass clazz, jlong objHandle, jfloat left, jfloat top,
                      jfloat right, jfloat bottom, jfloat startAngle, jfloat sweepAngle,
                      jboolean forceMoveTo) {
        SkRect oval = SkRect::MakeLTRB(left, top, right, bottom);
        AsPathWrapper(objHandle)->arcTo(oval, startAngle, sweepAngle, forceMoveTo);
    }

    static void close(JNIEnv* env, jclass clazz, jlong objHandle) {
        AsPathWrapper(objHandle)->close();
    }

    static void addRect(JNIEnv* env, jclass clazz, jlong objHandle, jfloat left, jfloat top,
                        jfloat right, jfloat bottom, jint dirHandle) {
        SkPathDirection dir = static_cast<SkPathDirection>(dirHandle);
        SkRect rect = SkRect::MakeLTRB(left, top, right, bottom);
        AsPathWrapper(objHandle)->addRect(rect, dir);
    }

    static void addOval(JNIEnv* env, jclass clazz, jlong objHandle, jfloat left, jfloat top,
                        jfloat right, jfloat bottom, jint dirHandle) {
        SkPathDirection dir = static_cast<SkPathDirection>(dirHandle);
        SkRect oval = SkRect::MakeLTRB(left, top, right, bottom);
        AsPathWrapper(objHandle)->addOval(oval, dir);
    }

    static void addCircle(JNIEnv* env, jclass clazz, jlong objHandle, jfloat x, jfloat y,
                          jfloat radius, jint dirHandle) {
        SkPathDirection dir = static_cast<SkPathDirection>(dirHandle);
        AsPathWrapper(objHandle)->addCircle(x, y, radius, dir);
    }

    static void addArc(JNIEnv* env, jclass clazz, jlong objHandle, jfloat left, jfloat top,
            jfloat right, jfloat bottom, jfloat startAngle, jfloat sweepAngle) {
        SkRect oval = SkRect::MakeLTRB(left, top, right, bottom);
        AsPathWrapper(objHandle)->addArc(oval, startAngle, sweepAngle);
    }

    static void addRoundRectXY(JNIEnv* env, jclass clazz, jlong objHandle, jfloat left, jfloat top,
            jfloat right, jfloat bottom, jfloat rx, jfloat ry, jint dirHandle) {
        SkRRect rrect = SkRRect::MakeRectXY(SkRect::MakeLTRB(left, top, right, bottom), rx, ry);
        SkPathDirection dir = static_cast<SkPathDirection>(dirHandle);
        AsPathWrapper(objHandle)->addRoundRect(rrect, dir);
    }

    static void addRoundRect8(JNIEnv* env, jclass clazz, jlong objHandle, jfloat left, jfloat top,
                              jfloat right, jfloat bottom, jfloatArray array, jint dirHandle) {
        SkPathDirection dir = static_cast<SkPathDirection>(dirHandle);
        AutoJavaFloatArray  afa(env, array, 8);
        const float* src = afa.ptr();
        // Newer Skia APIs take 4 SkVectors.
        const SkVector radii[] = {
                {src[0], src[1]},
                {src[2], src[3]},
                {src[4], src[5]},
                {src[6], src[7]},
        };
        SkRRect rrect = SkRRect::MakeRectRadii(SkRect::MakeLTRB(left, top, right, bottom), radii);
        AsPathWrapper(objHandle)->addRoundRect(rrect, dir);
    }

    static void addPath__PathFF(JNIEnv* env, jclass clazz, jlong objHandle, jlong srcHandle,
            jfloat dx, jfloat dy) {
        auto* obj = AsPathWrapper(objHandle);
        auto* src = AsPathWrapper(srcHandle);
        obj->addPath(*src, SkMatrix::Translate(dx, dy));
    }

    static void addPath__Path(JNIEnv* env, jclass clazz, jlong objHandle, jlong srcHandle) {
        auto* obj = AsPathWrapper(objHandle);
        auto* src = AsPathWrapper(srcHandle);
        obj->addPath(*src, SkMatrix::I());
    }

    static void addPath__PathMatrix(JNIEnv* env, jclass clazz, jlong objHandle, jlong srcHandle,
            jlong matrixHandle) {
        auto* obj = AsPathWrapper(objHandle);
        auto* src = AsPathWrapper(srcHandle);
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        obj->addPath(*src, *matrix);
    }

    static void offset__FF(JNIEnv* env, jclass clazz, jlong objHandle, jfloat dx, jfloat dy) {
        AsPathWrapper(objHandle)->offset(dx, dy);
    }

    static void setLastPoint(JNIEnv* env, jclass clazz, jlong objHandle, jfloat dx, jfloat dy) {
        AsPathWrapper(objHandle)->setLastPt(dx, dy);
    }

    static jboolean interpolate(JNIEnv* env, jclass clazz, jlong startHandle, jlong endHandle,
                                jfloat t, jlong interpolatedHandle) {
        auto* startPath = AsPathWrapper(startHandle);
        auto* endPath = AsPathWrapper(endHandle);
        auto* interpolatedPath = AsPathWrapper(interpolatedHandle);
        return startPath->interpolate(*endPath, t, interpolatedPath);
    }

    static void transform__MatrixPath(JNIEnv* env, jclass clazz, jlong objHandle, jlong matrixHandle,
            jlong dstHandle) {
        auto* obj = AsPathWrapper(objHandle);
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        auto* dst = AsPathWrapper(dstHandle);
        obj->transform(*matrix, dst);
    }

    static void transform__Matrix(JNIEnv* env, jclass clazz, jlong objHandle, jlong matrixHandle) {
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        AsPathWrapper(objHandle)->transform(*matrix);
    }

    static jboolean op(JNIEnv* env, jclass clazz, jlong p1Handle, jlong p2Handle, jint opHandle,
            jlong rHandle) {
        const SkPath* p1 = AsSkPath(p1Handle);
        const SkPath* p2 = AsSkPath(p2Handle);
        SkPathOp op = static_cast<SkPathOp>(opHandle);
        SkPath* r = AsSkPath(rHandle);
        return Op(*p1, *p2, op, r);
     }

    typedef SkPoint (*bezierCalculation)(float t, const SkPoint* points);

    static void addMove(std::vector<SkPoint>& segmentPoints, std::vector<float>& lengths,
            const SkPoint& point) {
        float length = 0;
        if (!lengths.empty()) {
            length = lengths.back();
        }
        segmentPoints.push_back(point);
        lengths.push_back(length);
    }

    static void addLine(std::vector<SkPoint>& segmentPoints, std::vector<float>& lengths,
            const SkPoint& toPoint) {
        if (segmentPoints.empty()) {
            segmentPoints.push_back(SkPoint::Make(0, 0));
            lengths.push_back(0);
        } else if (segmentPoints.back() == toPoint) {
            return; // Empty line
        }
        float length = lengths.back() + SkPoint::Distance(segmentPoints.back(), toPoint);
        segmentPoints.push_back(toPoint);
        lengths.push_back(length);
    }

    static float cubicCoordinateCalculation(float t, float p0, float p1, float p2, float p3) {
        float oneMinusT = 1 - t;
        float oneMinusTSquared = oneMinusT * oneMinusT;
        float oneMinusTCubed = oneMinusTSquared * oneMinusT;
        float tSquared = t * t;
        float tCubed = tSquared * t;
        return (oneMinusTCubed * p0) + (3 * oneMinusTSquared * t * p1)
                + (3 * oneMinusT * tSquared * p2) + (tCubed * p3);
    }

    static SkPoint cubicBezierCalculation(float t, const SkPoint* points) {
        float x = cubicCoordinateCalculation(t, points[0].x(), points[1].x(),
            points[2].x(), points[3].x());
        float y = cubicCoordinateCalculation(t, points[0].y(), points[1].y(),
            points[2].y(), points[3].y());
        return SkPoint::Make(x, y);
    }

    static float quadraticCoordinateCalculation(float t, float p0, float p1, float p2) {
        float oneMinusT = 1 - t;
        return oneMinusT * ((oneMinusT * p0) + (t * p1)) + t * ((oneMinusT * p1) + (t * p2));
    }

    static SkPoint quadraticBezierCalculation(float t, const SkPoint* points) {
        float x = quadraticCoordinateCalculation(t, points[0].x(), points[1].x(), points[2].x());
        float y = quadraticCoordinateCalculation(t, points[0].y(), points[1].y(), points[2].y());
        return SkPoint::Make(x, y);
    }

    // Subdivide a section of the Bezier curve, set the mid-point and the mid-t value.
    // Returns true if further subdivision is necessary as defined by errorSquared.
    static bool subdividePoints(const SkPoint* points, bezierCalculation bezierFunction,
            float t0, const SkPoint &p0, float t1, const SkPoint &p1,
            float& midT, SkPoint &midPoint, float errorSquared) {
        midT = (t1 + t0) / 2;
        float midX = (p1.x() + p0.x()) / 2;
        float midY = (p1.y() + p0.y()) / 2;

        midPoint = (*bezierFunction)(midT, points);
        float xError = midPoint.x() - midX;
        float yError = midPoint.y() - midY;
        float midErrorSquared = (xError * xError) + (yError * yError);
        return midErrorSquared > errorSquared;
    }

    // Divides Bezier curves until linear interpolation is very close to accurate, using
    // errorSquared as a metric. Cubic Bezier curves can have an inflection point that improperly
    // short-circuit subdivision. If you imagine an S shape, the top and bottom points being the
    // starting and end points, linear interpolation would mark the center where the curve places
    // the point. It is clearly not the case that we can linearly interpolate at that point.
    // doubleCheckDivision forces a second examination between subdivisions to ensure that linear
    // interpolation works.
    static void addBezier(const SkPoint* points,
            bezierCalculation bezierFunction, std::vector<SkPoint>& segmentPoints,
            std::vector<float>& lengths, float errorSquared, bool doubleCheckDivision) {
        typedef std::map<float, SkPoint> PointMap;
        PointMap tToPoint;

        tToPoint[0] = (*bezierFunction)(0, points);
        tToPoint[1] = (*bezierFunction)(1, points);

        PointMap::iterator iter = tToPoint.begin();
        PointMap::iterator next = iter;
        ++next;
        while (next != tToPoint.end()) {
            bool needsSubdivision = true;
            SkPoint midPoint;
            do {
                float midT;
                needsSubdivision = subdividePoints(points, bezierFunction, iter->first,
                    iter->second, next->first, next->second, midT, midPoint, errorSquared);
                if (!needsSubdivision && doubleCheckDivision) {
                    SkPoint quarterPoint;
                    float quarterT;
                    needsSubdivision = subdividePoints(points, bezierFunction, iter->first,
                        iter->second, midT, midPoint, quarterT, quarterPoint, errorSquared);
                    if (needsSubdivision) {
                        // Found an inflection point. No need to double-check.
                        doubleCheckDivision = false;
                    }
                }
                if (needsSubdivision) {
                    next = tToPoint.insert(iter, PointMap::value_type(midT, midPoint));
                }
            } while (needsSubdivision);
            iter = next;
            next++;
        }

        // Now that each division can use linear interpolation with less than the allowed error
        for (iter = tToPoint.begin(); iter != tToPoint.end(); ++iter) {
            addLine(segmentPoints, lengths, iter->second);
        }
    }

    static void createVerbSegments(const SkPathIter::Rec& pathRec,
                                   std::vector<SkPoint>& segmentPoints, std::vector<float>& lengths,
                                   float errorSquared, float errorConic) {
        switch (pathRec.fVerb) {
            case SkPathVerb::kMove:
                addMove(segmentPoints, lengths, pathRec.fPoints[0]);
                break;
            case SkPathVerb::kClose:
                addLine(segmentPoints, lengths, pathRec.fPoints[1]);
                break;
            case SkPathVerb::kLine:
                addLine(segmentPoints, lengths, pathRec.fPoints[1]);
                break;
            case SkPathVerb::kQuad:
                addBezier(pathRec.fPoints.data(), quadraticBezierCalculation, segmentPoints,
                          lengths, errorSquared, false);
                break;
            case SkPathVerb::kCubic:
                addBezier(pathRec.fPoints.data(), cubicBezierCalculation, segmentPoints, lengths,
                          errorSquared, true);
                break;
            case SkPathVerb::kConic: {
                SkAutoConicToQuads converter;
                const SkPoint* quads = converter.computeQuads(pathRec.fPoints.data(),
                                                              pathRec.fConicWeight, errorConic);
                for (int i = 0; i < converter.countQuads(); i++) {
                    // Note: offset each subsequent quad by 2, since end points are shared
                    const SkPoint* quad = quads + i * 2;
                    addBezier(quad, quadraticBezierCalculation, segmentPoints, lengths,
                        errorConic, false);
                }
                break;
            }
            default:
                static_assert(SkPath::kMove_Verb == 0
                                && SkPath::kLine_Verb == 1
                                && SkPath::kQuad_Verb == 2
                                && SkPath::kConic_Verb == 3
                                && SkPath::kCubic_Verb == 4
                                && SkPath::kClose_Verb == 5
                                && SkPath::kDone_Verb == 6,
                        "Path enum changed, new types may have been added.");
                break;
        }
    }

    // Returns a float[] with each point along the path represented by 3 floats
    // * fractional length along the path that the point resides
    // * x coordinate
    // * y coordinate
    // Note that more than one point may have the same length along the path in
    // the case of a move.
    // NULL can be returned if the Path is empty.
    static jfloatArray approximate(JNIEnv* env, jclass clazz, jlong pathHandle,
            float acceptableError) {
        auto* path = AsPathWrapper(pathHandle);
        SkASSERT(path);
        SkPathIter pathIter = path->iter();
        std::vector<SkPoint> segmentPoints;
        std::vector<float> lengths;
        float errorSquared = acceptableError * acceptableError;
        float errorConic = acceptableError / 2; // somewhat arbitrary

        while (auto rec = pathIter.next()) {
            createVerbSegments(*rec, segmentPoints, lengths, errorSquared, errorConic);
        }

        if (segmentPoints.empty()) {
            if (path->verbs().size() == 1) {
                addMove(segmentPoints, lengths, path->points()[0]);
            } else {
                // Invalid or empty path. Fall back to point(0,0)
                addMove(segmentPoints, lengths, SkPoint());
            }
        }

        float totalLength = lengths.back();
        if (totalLength == 0) {
            // Lone Move instructions should still be able to animate at the same value.
            segmentPoints.push_back(segmentPoints.back());
            lengths.push_back(1);
            totalLength = 1;
        }

        size_t numPoints = segmentPoints.size();
        size_t approximationArraySize = numPoints * 3;

        float* approximation = new float[approximationArraySize];

        int approximationIndex = 0;
        for (size_t i = 0; i < numPoints; i++) {
            const SkPoint& point = segmentPoints[i];
            approximation[approximationIndex++] = lengths[i] / totalLength;
            approximation[approximationIndex++] = point.x();
            approximation[approximationIndex++] = point.y();
        }

        jfloatArray result = env->NewFloatArray(approximationArraySize);
        env->SetFloatArrayRegion(result, 0, approximationArraySize, approximation);
        delete[] approximation;
        return result;
    }

    // ---------------- @FastNative -----------------------------

    static jboolean isRect(JNIEnv* env, jclass clazz, jlong objHandle, jobject jrect) {
        SkRect rect;
        jboolean result = AsPathWrapper(objHandle)->isRect(&rect);
        if (jrect) {
            GraphicsJNI::rect_to_jrectf(rect, env, jrect);
        }
        return result;
    }

    // ---------------- @CriticalNative -------------------------

    static jint getGenerationID(CRITICAL_JNI_PARAMS_COMMA jlong pathHandle) {
        return (AsPathWrapper(pathHandle)->getGenerationID());
    }

    static jboolean isInterpolatable(CRITICAL_JNI_PARAMS_COMMA jlong startHandle, jlong endHandle) {
        auto* startPath = AsPathWrapper(startHandle);
        auto* endPath = AsPathWrapper(endHandle);
        return startPath->isInterpolatable(*endPath);
    }

    static void reset(CRITICAL_JNI_PARAMS_COMMA jlong objHandle) {
        AsPathWrapper(objHandle)->reset();
    }

    static void rewind(CRITICAL_JNI_PARAMS_COMMA jlong objHandle) {
        AsPathWrapper(objHandle)->rewind();
    }

    static jboolean isEmpty(CRITICAL_JNI_PARAMS_COMMA jlong objHandle) {
        return AsPathWrapper(objHandle)->isEmpty();
    }

    static jboolean isConvex(CRITICAL_JNI_PARAMS_COMMA jlong objHandle) {
        return AsPathWrapper(objHandle)->isConvex();
    }

    static jint getFillType(CRITICAL_JNI_PARAMS_COMMA jlong objHandle) {
        return static_cast<int>(AsPathWrapper(objHandle)->getFillType());
    }

    static void setFillType(CRITICAL_JNI_PARAMS_COMMA jlong pathHandle, jint ftHandle) {
        ;
        SkPathFillType ft = static_cast<SkPathFillType>(ftHandle);
        AsPathWrapper(pathHandle)->setFillType(ft);
    }
};

static const JNINativeMethod methods[] = {
        {"nInit", "()J", (void*)SkPathGlue::init},
        {"nInit", "(J)J", (void*)SkPathGlue::init_Path},
        {"nGetFinalizer", "()J", (void*)SkPathGlue::getFinalizer},
        {"nSet", "(JJ)V", (void*)SkPathGlue::set},
        {"nComputeBounds", "(JLandroid/graphics/RectF;)V", (void*)SkPathGlue::computeBounds},
        {"nIncReserve", "(JI)V", (void*)SkPathGlue::incReserve},
        {"nMoveTo", "(JFF)V", (void*)SkPathGlue::moveTo__FF},
        {"nRMoveTo", "(JFF)V", (void*)SkPathGlue::rMoveTo},
        {"nLineTo", "(JFF)V", (void*)SkPathGlue::lineTo__FF},
        {"nRLineTo", "(JFF)V", (void*)SkPathGlue::rLineTo},
        {"nQuadTo", "(JFFFF)V", (void*)SkPathGlue::quadTo__FFFF},
        {"nRQuadTo", "(JFFFF)V", (void*)SkPathGlue::rQuadTo},
        {"nConicTo", "(JFFFFF)V", (void*)SkPathGlue::conicTo},
        {"nRConicTo", "(JFFFFF)V", (void*)SkPathGlue::rConicTo},
        {"nCubicTo", "(JFFFFFF)V", (void*)SkPathGlue::cubicTo__FFFFFF},
        {"nRCubicTo", "(JFFFFFF)V", (void*)SkPathGlue::rCubicTo},
        {"nArcTo", "(JFFFFFFZ)V", (void*)SkPathGlue::arcTo},
        {"nClose", "(J)V", (void*)SkPathGlue::close},
        {"nAddRect", "(JFFFFI)V", (void*)SkPathGlue::addRect},
        {"nAddOval", "(JFFFFI)V", (void*)SkPathGlue::addOval},
        {"nAddCircle", "(JFFFI)V", (void*)SkPathGlue::addCircle},
        {"nAddArc", "(JFFFFFF)V", (void*)SkPathGlue::addArc},
        {"nAddRoundRect", "(JFFFFFFI)V", (void*)SkPathGlue::addRoundRectXY},
        {"nAddRoundRect", "(JFFFF[FI)V", (void*)SkPathGlue::addRoundRect8},
        {"nAddPath", "(JJFF)V", (void*)SkPathGlue::addPath__PathFF},
        {"nAddPath", "(JJ)V", (void*)SkPathGlue::addPath__Path},
        {"nAddPath", "(JJJ)V", (void*)SkPathGlue::addPath__PathMatrix},
        {"nInterpolate", "(JJFJ)Z", (void*)SkPathGlue::interpolate},
        {"nOffset", "(JFF)V", (void*)SkPathGlue::offset__FF},
        {"nSetLastPoint", "(JFF)V", (void*)SkPathGlue::setLastPoint},
        {"nTransform", "(JJJ)V", (void*)SkPathGlue::transform__MatrixPath},
        {"nTransform", "(JJ)V", (void*)SkPathGlue::transform__Matrix},
        {"nOp", "(JJIJ)Z", (void*)SkPathGlue::op},
        {"nApproximate", "(JF)[F", (void*)SkPathGlue::approximate},

        // ------- @FastNative below here ----------------------
        {"nIsRect", "(JLandroid/graphics/RectF;)Z", (void*)SkPathGlue::isRect},

        // ------- @CriticalNative below here ------------------
        {"nGetGenerationID", "(J)I", (void*)SkPathGlue::getGenerationID},
        {"nIsInterpolatable", "(JJ)Z", (void*)SkPathGlue::isInterpolatable},
        {"nReset", "(J)V", (void*)SkPathGlue::reset},
        {"nRewind", "(J)V", (void*)SkPathGlue::rewind},
        {"nIsEmpty", "(J)Z", (void*)SkPathGlue::isEmpty},
        {"nIsConvex", "(J)Z", (void*)SkPathGlue::isConvex},
        {"nGetFillType", "(J)I", (void*)SkPathGlue::getFillType},
        {"nSetFillType", "(JI)V", (void*)SkPathGlue::setFillType},
};

int register_android_graphics_Path(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/graphics/Path", methods, NELEM(methods));

    static_assert(0 == (int)SkPathDirection::kCW,  "direction_mismatch");
    static_assert(1 == (int)SkPathDirection::kCCW, "direction_mismatch");
}

}
