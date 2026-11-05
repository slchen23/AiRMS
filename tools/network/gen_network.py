"""產生 AiRMS 路網設定檔 network.json。

座標取自簡報 webMonitor打版20260708.pptx 第 10 頁（EMU / 10000），再整體平移成正值。
路線（每個方向一條）以轉角折線表示，轉角用圓弧近似；每個點附上里程（公尺），
前端依里程內插把路段速度畫在對應的線段上。
"""
import json
import math
import sys

DX, DY = 560.0, 320.0


def P(x, y):
    return (x + DX, y + DY)


def rounded(corners, radii, step_deg=10):
    """把折線轉角換成圓弧點。radii[i] 對應 corners[i]（頭尾忽略）。"""
    out = [corners[0]]
    for i in range(1, len(corners) - 1):
        a, b, c = corners[i - 1], corners[i], corners[i + 1]
        r = radii[i]
        v1 = (a[0] - b[0], a[1] - b[1])
        v2 = (c[0] - b[0], c[1] - b[1])
        l1 = math.hypot(*v1)
        l2 = math.hypot(*v2)
        u1 = (v1[0] / l1, v1[1] / l1)
        u2 = (v2[0] / l2, v2[1] / l2)
        # 90 度轉角：切點距轉角 r
        t1 = (b[0] + u1[0] * r, b[1] + u1[1] * r)
        t2 = (b[0] + u2[0] * r, b[1] + u2[1] * r)
        center = (t1[0] + u2[0] * r, t1[1] + u2[1] * r)
        ang1 = math.atan2(t1[1] - center[1], t1[0] - center[0])
        ang2 = math.atan2(t2[1] - center[1], t2[0] - center[0])
        d = ang2 - ang1
        while d > math.pi:
            d -= 2 * math.pi
        while d < -math.pi:
            d += 2 * math.pi
        n = max(2, int(abs(math.degrees(d)) / step_deg))
        for k in range(n + 1):
            t = ang1 + d * k / n
            out.append((center[0] + r * math.cos(t), center[1] + r * math.sin(t)))
    out.append(corners[-1])
    return out


def project(points, p):
    """點投影到折線上：回傳 (累積長度, 投影點)。"""
    best = None
    acc = 0.0
    for i in range(len(points) - 1):
        a, b = points[i], points[i + 1]
        dx, dy = b[0] - a[0], b[1] - a[1]
        seg = math.hypot(dx, dy)
        if seg == 0:
            continue
        t = max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / (seg * seg)))
        q = (a[0] + dx * t, a[1] + dy * t)
        d = math.hypot(p[0] - q[0], p[1] - q[1])
        if best is None or d < best[0]:
            best = (d, acc + seg * t, q)
        acc += seg
    return best


def milepost_points(points, anchors):
    """anchors: [(累積長度, 里程)]，依累積長度遞增。回傳每個點的里程（頭尾外插）。"""
    cum = [0.0]
    for i in range(1, len(points)):
        cum.append(cum[-1] + math.hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1]))
    anchors = sorted(anchors)
    for i in range(1, len(anchors)):
        if not anchors[i][1] > anchors[i - 1][1]:
            raise SystemExit(f"里程沒有隨路線遞增：{anchors[i - 1]} → {anchors[i]}")
    out = []
    for s in cum:
        if s <= anchors[0][0]:
            (s0, m0), (s1, m1) = anchors[0], anchors[1]
        elif s >= anchors[-1][0]:
            (s0, m0), (s1, m1) = anchors[-2], anchors[-1]
        else:
            j = next(k for k in range(1, len(anchors)) if anchors[k][0] >= s)
            (s0, m0), (s1, m1) = anchors[j - 1], anchors[j]
        m = m0 + (m1 - m0) * (s - s0) / (s1 - s0)
        out.append(round(m))
    return out


# ------------------------------------------------------------------ 路線幾何（里程遞增方向）

# 國1：北端（新竹系統）在右下，往南：下排往左 → 中間直行往上 → 上排往左到大林。
F1_S = rounded([P(1745, 627.0), P(916.2, 627.0), P(916.2, 163.0), P(-415, 163.0)], [0, 55.8, 72.0, 0])
F1_N = rounded([P(1745, 679.6), P(864.5, 679.6), P(864.5, 209.0), P(-415, 209.0)], [0, 72.0, 45.7, 0])
# 國3：北端（香山）在右上，往南：上排往左 → 中間直行往下 → 下排往左到古坑系統。
F3_S = rounded([P(1640, -204.5), P(555.6, -204.5), P(555.6, 783.8), P(-415, 783.8)], [0, 72.0, 32.4, 0])
F3_N = rounded([P(1640, -148.6), P(610.8, -148.6), P(610.8, 839.8), P(-415, 839.8)], [0, 54.0, 56.0, 0])

ROW = {
    # 國1
    ("1", "top"): {"S": 163.0, "N": 209.0},
    ("1", "bottom"): {"S": 627.0, "N": 679.6},
    ("1", "vertical"): {"S": 916.2, "N": 864.5},
    # 國3
    ("3", "top"): {"S": -204.5, "N": -148.6},
    ("3", "bottom"): {"S": 783.8, "N": 839.8},
    ("3", "vertical"): {"S": 555.6, "N": 610.8},
}

# 標籤位置：橫排在下方（或上方）直書；直行在左或右橫書。
LABEL = {
    ("1", "top"): {"layout": "v", "y": 247},        # 名稱起點 y，里程在名稱下方
    ("1", "bottom"): {"layout": "v", "y": 716},
    ("3", "top"): {"layout": "v", "y": -110},
    ("3", "bottom"): {"layout": "v", "y": 588},
}

R = None  # 沒有 RMS 的一般匝道（白圈、空白）
OUT = "outside"  # 「外」：範圍外，沒有儀控

# 節點：(里程, 名稱, 區段, 位置, 南下, 北上, 標籤設定)
#   位置：橫排為 x，直行為 y
#   南下／北上：None = 不畫圈；"outside"；[] = 白圈無 RMS；[eqId,...] = RMS（2、3 個表示切格）
N1 = [
    (99400, "新竹系統", "bottom", 1678.0, OUT, OUT, {}),
    (110400, "頭份", "bottom", 1611.0, ["RMS-N1-S-110-I-NS-1-南下入口-頭份"], ["RMS-N1-N-110-I-SN-1-北上入口-頭份"], {}),
    (118000, "造橋", "bottom", 1536.8, None, [],
     {"note": "新增匝道，尚無 RMS 資料；有資料後在「路網匝道」管理頁填入設備代碼"}),
    (126200, "頭屋", "bottom", 1462.0, ["RMS-N1-S-125.940-ES-L-南下入口-頭屋"], ["RMS-N1-N-125.280-EN-L-北上入口-頭屋"], {"km": "125K"}),
    (132800, "苗栗", "bottom", 1392.5, ["RMS-N1-S-132-I-NS-1-南下入口-苗栗"],
     ["RMS-N1-N-132-I-EN-1A-北上入口-公館", "RMS-N1-N-132-I-WN-1B-北上入口-苗栗"], {}),
    (140080, "銅鑼", "bottom", 1308.5, ["RMS-N1-S-140-I-NS-1-南下入口-銅鑼"], ["RMS-N1-N-139-I-SN-1-北上入口-銅鑼"], {}),
    (150200, "三義", "bottom", 1237.4, ["RMS-N1-S-150-I-NS-1-南下入口-三義"], ["RMS-N1-N-150-I-SN-1-北上入口-三義"], {}),
    (159500, "泰安服務區", "bottom", 1162.0, None, None, {"service": True}),
    (160600, "后里", "bottom", 1093.9, ["RMS-N1-S-160-I-WS-1-南下入口-后里"], ["RMS-N1-N-160-I-WN-1-北上入口-后里"], {}),
    (165500, "台中系統", "bottom", 1020.3, ["RMS-N1-S-165-I-NS-1-南下入口-台中系統"], ["RMS-N1-N-165-I-SN-1-北上入口-台中系統"], {}),
    (168100, "豐原", "bottom", 950.0, ["RMS-N1-S-168-I-ES-1-南下入口-豐原"], ["RMS-N1-N-168-I-EN-1-北上入口-豐原"], {}),
    (173200, "大雅系統", "corner", (864.5, 596.0), None, ["RMS-N1-N-172-I-EN-1-北上入口-大雅系統"],
     {"km": "172K", "label": {"layout": "v", "x": 864.5, "y": 716}}),
    # 北上入口沒有 RMS（開放資料中沒有這台）；舊 view.html 不顯示，預設隱藏，可在「路網匝道」管理頁打開
    (174200, "大雅", "vertical", 511.1, ["RMS-N1-S-174-I-NS-1-南下入口-大雅"], [],
     {"hidden": ["N"]}),
    (178600, "台中", "vertical", 433.2, ["RMS-N1-S-178-I-NS-1-南下入口-台中"], ["RMS-N1-N-178-I-SN-1-北上入口-台中"], {}),
    (181400, "南屯", "vertical", 358.4, ["RMS-N1-S-181-I-NS-1-南下入口-南屯"], ["RMS-N1-N-181-I-SN-1-北上入口-南屯"], {}),
    (189400, "王田", "vertical", 279.3, ["RMS-N1-S-189-I-ES-1-南下入口-王田"], ["RMS-N1-N-189-I-EN-1-北上入口-王田"], {}),
    (192800, "彰化系統", "top", 449.7, ["RMS-N1-S-192-I-ES-1-南下入口-彰化系統"], ["RMS-N1-N-192-I-WN-1-北上入口-彰化系統"], {}),
    (198400, "彰化", "top", 378.2, ["RMS-N1-S-198-I-NS-1-南下入口-彰化"], ["RMS-N1-N-198-I-SN-1-北上入口-彰化"], {}),
    (207700, "埔鹽系統", "top", 306.7,
     ["RMS-N1-S-207-I-ES-1A-南下入口-埔鹽系統", "RMS-N1-S-207-I-WS-1B-南下入口-埔鹽系統"],
     ["RMS-N1-N-207-I-EN-21-北上入口-埔鹽系統"], {}),
    (211000, "員林", "top", 232.3, ["RMS-N1-S-211-I-NS-1-南下入口-員林"], ["RMS-N1-N-211-I-SN-1-北上入口-員林"], {}),
    (220100, "北斗", "top", 151.4, ["RMS-N1-S-220-I-WS-1-南下入口-北斗"], ["RMS-N1-N-220-I-EN-1-北上入口-北斗"], {}),
    (228800, "西螺服務區", "top", 50.0, None, None, {"service": True, "km": "229K"}),
    (230500, "西螺", "top", -16.6, ["RMS-N1-S-230-I-NS-1-南下入口-西螺"], ["RMS-N1-N-230-I-SN-1-北上入口-西螺"], {}),
    (236000, "虎尾", "top", -98.8, ["RMS-N1-S-236-I-NS-1-南下入口-虎尾"], ["RMS-N1-N-235-I-SN-1-北上入口-虎尾"], {"km": "235K"}),
    (240600, "斗南", "top", -173.1, ["RMS-N1-S-240-I-NS-1-南下入口-斗南"], ["RMS-N1-N-240-I-SN-1-北上入口-斗南"], {}),
    (243800, "雲林系統", "top", -249.7, ["RMS-N1-S-243-I-NS-1-南下入口-雲林系統"], ["RMS-N1-N-243-I-SN-1-北上入口-雲林系統"], {}),
    (250300, "大林", "top", -325.6, ["RMS-N1-S-250-I-NS-1-南下入口-大林"], ["RMS-N1-N-250-I-SN-1-北上入口-大林"], {}),
]

N3 = [
    (109600, "香山", "top", 1579.0, OUT, OUT, {}),
    (115000, "西濱", "top", 1494.0, OUT, ["RMS-N3-N-115-I-EN-1-北上入口-西濱"], {}),
    (119100, "竹南", "top", 1413.4, ["RMS-N3-S-119-I-NS-1-南下入口-竹南"], ["RMS-N3-N-119-I-SN-1-北上入口-竹南"], {}),
    (124500, "大山", "top", 1333.4, ["RMS-N3-S-124-I-NS-1-南下入口-大山"], ["RMS-N3-N-124-I-SN-1-北上入口-大山"], {}),
    (130200, "後龍", "top", 1250.7, ["RMS-N3-S-130-I-NS-1-南下入口-後龍"], ["RMS-N3-N-130-I-SN-1-北上入口-後龍"], {}),
    (134800, "西湖服務區", "top", 1160.0, None, None, {"service": True}),
    (144300, "通霄", "top", 1096.9, ["RMS-N3-S-144-I-NS-1-南下入口-通霄"], ["RMS-N3-N-144-I-SN-1-北上入口-通霄"], {}),
    (156800, "苑裡", "top", 1018.4, ["RMS-N3-S-156-I-NS-1-南下入口-苑裡"], ["RMS-N3-N-156-I-SN-1-北上入口-苑裡"], {}),
    (164200, "大甲", "top", 939.3, ["RMS-N3-S-164-I-NS-1-南下入口-大甲"], ["RMS-N3-N-164-I-SN-1-北上入口-大甲"], {}),
    (169000, "中港系統", "top", 859.3, ["RMS-N3-S-169-I-NS-1-南下入口-中港系統"], ["RMS-N3-N-169-I-SN-1-北上入口-中港系統"], {}),
    (172400, "清水服務區", "top", 715.0, None, None, {"service": True}),
    (176100, "沙鹿", "vertical", -122.2, ["RMS-N3-S-176-I-NS-1-南下入口-沙鹿"], ["RMS-N3-N-176-I-SN-1-北上入口-沙鹿"], {"side": "left"}),
    (182800, "龍井", "vertical", -53.4,
     ["RMS-N3-S-182-I-ES-33A-南下入口-龍井", "RMS-N3-S-182-I-WS-21B-南下入口-龍井"],
     ["RMS-N3-N-182-I-SN-1-北上入口-龍井"], {"side": "left"}),
    (191600, "和美", "vertical", 18.7,
     ["RMS-N3-S-191-I-NS-22A-南下入口-和美", "RMS-N3-S-191-I-SS-21B-南下入口-伸港"],
     ["RMS-N3-N-191-I-SN-1-北上入口-和美"], {"side": "left"}),
    (196800, "彰化系統", "vertical", 85.5, ["RMS-N3-S-196-I-NS-1-南下入口-彰化系統"], ["RMS-N3-N-196-I-SN-1-北上入口-彰化系統"], {}),
    (202100, "快官", "vertical", 280.0, ["RMS-N3-S-202-I-NS-1-南下入口-快官"], ["RMS-N3-N-202-I-SN-1-北上入口-快官"], {}),
    (207300, "烏日", "vertical", 357.8, ["RMS-N3-S-207-I-NS-1-南下入口-烏日"], ["RMS-N3-N-207-I-SN-1-北上入口-烏日"], {}),
    (209000, "中投", "vertical", 432.6, ["RMS-N3-S-209-I-NS-1-南下入口-中投"], ["RMS-N3-N-209-I-EN-1-北上入口-中投"], {}),
    (211300, "霧峰", "vertical", 510.6,
     ["RMS-N3-S-211-I-NS-22-霧峰-環道", "RMS-N3-S-212-I-NS-22-霧峰-環道", "RMS-N3-S-212-I-NS-22-霧峰-環道-國6專用道"],
     ["RMS-N3-N-210-I-EN-22-霧峰-環道"], {}),
    (214000, "霧峰系統", "vertical", 587.4, ["RMS-N3-S-214-I-WS-1-南下入口-霧峰系統"], ["RMS-N3-N-214-I-WN-1-北上入口-霧峰系統"], {}),
    (217200, "草屯", "vertical", 678.0, ["RMS-N3-S-217-I-NS-1-南下入口-草屯"], ["RMS-N3-N-217-I-SN-1-北上入口-草屯"], {}),
    (222500, "中興系統", "corner", (555.6, 762.0), ["RMS-N3-S-223-I-ES-南下入口-中興系統"], ["RMS-N3-N-222-I-EN-北上入口-中興系統"],
     {"nPos": (610.8, 762.0), "label": {"layout": "h", "x": 642.0, "y": 762.0, "anchor": "start"}}),
    (224700, "中興", "bottom", 377.0, ["RMS-N3-S-224-I-NS-1-南下入口-中興"], ["RMS-N3-N-224-I-SN-1-北上入口-中興"], {}),
    (228600, "南投", "bottom", 303.0, ["RMS-N3-S-228.500-WS-L-南下入口-南投"], ["RMS-N3-N-227.300-WN-L-北上入口-南投"], {}),
    (231400, "南投服務區", "bottom", 200.0, None, None, {"service": True}),
    (236800, "名間", "bottom", 122.5, ["RMS-N3-S-237-I-WS-1-南下入口-名間"], ["RMS-N3-N-237-I-WN-1-北上入口-名間"], {"km": "237K"}),
    (243700, "竹山", "bottom", 30.0, ["RMS-N3-S-243-I-ES-1-南下入口-竹山"], ["RMS-N3-N-243-I-EN-1-北上入口-竹山"], {}),
    (250100, "南雲", "bottom", -62.0, ["RMS-N3-S-250-I-NS-1-南下入口-南雲"], ["RMS-N3-N-250-I-SN-1-北上入口-南雲"], {}),
    (260300, "斗六", "bottom", -154.5, ["RMS-N3-S-260-I-NS-1-南下入口-斗六"], ["RMS-N3-N-260-I-SN-1-北上入口-斗六"], {}),
    (268232, "古坑(北)", "bottom", -244.0, OUT, ["RMS-N3-N-268-I-SN-1-北上入口-古坑(北)"], {"km": "268K"}),
    (269200, "古坑系統", "bottom", -342.0, OUT, OUT, {}),
]

ROUTES = {"1": {"S": F1_S, "N": F1_N}, "3": {"S": F3_S, "N": F3_N}}


def node_point(fw, part, pos, d, extra):
    if part == "corner":
        if d == "N" and "nPos" in extra:
            return P(*extra["nPos"])
        if d == "N":
            return P(*pos)
        return P(*pos) if fw == "3" else P(ROW[(fw, "vertical")]["S"], pos[1])
    row = ROW[(fw, part)]
    if part == "vertical":
        return P(row[d], pos)
    return P(pos, row[d])


def km_label(m, extra):
    """里程標示取整數公里（無條件捨去），少數依簡報另訂（extra["km"]）。"""
    if "km" in extra:
        return extra["km"]
    return f"{m // 1000}K"


def ramp(spec):
    if spec is None:
        return None
    if spec == OUT:
        return {"kind": "outside"}
    if len(spec) == 0:
        return {"kind": "plain"}
    return {"kind": "rms", "rms": spec}


def build_nodes(fw, items):
    nodes = []
    anchors = {"S": [], "N": []}
    for m, name, part, pos, s, n, extra in items:
        node = {
            "id": f"n{fw}-{m}",
            "freeway": fw,
            "name": name,
            "milepost": m,
            "km": km_label(m, extra),
        }
        if extra.get("service"):
            node["service"] = True
        if "note" in extra:
            node["note"] = extra["note"]
        ramps = {}
        for d, spec in (("S", s), ("N", n)):
            pt = node_point(fw, part, pos, d, extra)
            _, along, q = project(ROUTES[fw][d], pt)
            anchors[d].append((along, m, q))
            r = ramp(spec)
            if r is not None:
                # 每個匝道圈都有固定代碼與名稱，「路網匝道」管理頁用它來顯示／隱藏、修改 RMS 代碼
                r["key"] = f"n{fw}-{m}-{d.lower()}"
                r["label"] = f"國{fw}{'南下' if d == 'S' else '北上'} {name} {node['km']}"
                if d in extra.get("hidden", []):
                    r["visible"] = False
                r["x"] = round(pt[0], 1)
                r["y"] = round(pt[1], 1)
                ramps[d] = r
        node["ramps"] = ramps
        # 標籤
        if "label" in extra:
            lab = dict(extra["label"])
            lab["x"], lab["y"] = P(lab["x"], lab["y"])
        elif part in ("top", "bottom"):
            cfg = LABEL[(fw, part)]
            x = pos + DX
            lab = {"layout": "v", "x": x, "y": cfg["y"] + DY}
        else:  # vertical
            side = extra.get("side", "right")
            row = ROW[(fw, "vertical")]
            if side == "right":
                lab = {"layout": "h", "x": max(row["S"], row["N"]) + 32 + DX, "y": pos + DY, "anchor": "start"}
            else:
                lab = {"layout": "h", "x": min(row["S"], row["N"]) - 32 + DX, "y": pos + DY, "anchor": "end"}
        lab["x"] = round(lab["x"], 1)
        lab["y"] = round(lab["y"], 1)
        node["label"] = lab
        nodes.append(node)
    return nodes, anchors


def route_json(points, anchors, direction_id):
    """路線頂點加上各節點的投影點（里程精確），其餘頂點依長度內插里程。"""
    ms = milepost_points(points, [(a, m) for a, m, _ in anchors])
    cum = [0.0]
    for i in range(1, len(points)):
        cum.append(cum[-1] + math.hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1]))
    merged = [(c, p, m) for c, p, m in zip(cum, points, ms)] + [(a, q, m) for a, m, q in anchors]
    merged.sort(key=lambda t: (t[0], t[2]))
    out = []
    for c, p, m in merged:
        if out and abs(c - out[-1][0]) < 0.05:
            out[-1] = (c, p, m) if (c, p, m) in [(a, q, mm) for a, mm, q in anchors] else out[-1]
            continue
        out.append((c, p, m))
    for i in range(1, len(out)):
        if not out[i][2] > out[i - 1][2]:
            raise SystemExit(f"路線里程沒有遞增：{out[i - 1]} → {out[i]}")
    return {"directionId": direction_id, "points": [[round(p[0], 1), round(p[1], 1), m] for _, p, m in out]}


n1, a1 = build_nodes("1", N1)
n3, a3 = build_nodes("3", N3)

# 屏柵線（舊系統 PTConfig etcDevices 的 8 組；門檻可在系統設定頁調整）
def bell(x, y):
    bx, by = P(x, y)
    return {"x": round(bx, 1), "y": round(by, 1)}


SCREENLINES = [
    {"id": "n1-99-s", "label": "國1北往中", "freeway": "1", "direction": "S", "vdId": "VD-N1-S-109.250-M-LOOP",
     "volLimit": 5400, "volContinuous": 450, "bell": bell(1735, 573)},
    {"id": "n1-99-n", "label": "國1中往北", "freeway": "1", "direction": "N", "vdId": "VD-N1-N-101.510-M-LOOP",
     "volLimit": 5400, "volContinuous": 450, "bell": bell(1735, 733)},
    {"id": "n1-250-s", "label": "國1中往南", "freeway": "1", "direction": "S", "vdId": "VD-N1-S-249.200-M-LOOP",
     "volLimit": 4200, "volContinuous": 350, "bell": bell(-390, 110)},
    {"id": "n1-250-n", "label": "國1南往中", "freeway": "1", "direction": "N", "vdId": "VD-N1-N-244.780-M-LOOP",
     "volLimit": 3800, "volContinuous": 317, "bell": bell(-390, 262)},
    {"id": "n3-109-s", "label": "國3北往中", "freeway": "3", "direction": "S", "vdId": "VD-N3-S-117.349-M-LOOP",
     "volLimit": 5400, "volContinuous": 450, "bell": bell(1625, -258)},
    {"id": "n3-109-n", "label": "國3中往北", "freeway": "3", "direction": "N", "vdId": "VD-N3-N-111.636-M-RS",
     "volLimit": 5400, "volContinuous": 450, "bell": bell(1625, -95)},
    {"id": "n3-269-s", "label": "國3中往南", "freeway": "3", "direction": "S", "vdId": "VD-N3-S-266.885-M-LOOP",
     "volLimit": 4200, "volContinuous": 350, "bell": bell(-400, 730)},
    {"id": "n3-269-n", "label": "國3南往中", "freeway": "3", "direction": "N", "vdId": "VD-N3-N-261.545-M-LOOP",
     "volLimit": 3800, "volContinuous": 317, "bell": bell(-400, 880)},
]


def ends(fw, side, x, y_s, y_n, arrow_from, arrow_to, label_x, shield):
    sx, sy = P(*shield)
    return {
        "freeway": fw,
        "shield": {"x": round(sx, 1), "y": round(sy, 1), "size": 58},
        "labels": [
            {"text": "南下", "x": round(label_x + DX, 1), "y": round(y_s + DY, 1),
             "arrow": [round(arrow_from + DX, 1), round(arrow_to + DX, 1)]},
            {"text": "北上", "x": round(label_x + DX, 1), "y": round(y_n + DY, 1),
             "arrow": [round(arrow_to + DX, 1), round(arrow_from + DX, 1)]},
        ],
        "side": side,
    }


network = {
    "schemaVersion": 1,
    "title": "國道1號、國道3號（新竹系統～大林、香山～古坑系統）匝道儀控路網",
    "source": "webMonitor打版20260708.pptx 第 10 頁路網圖",
    "viewBox": [0, 0, 2450, 1230],
    "speedClasses": [
        {"key": "NO_DATA", "label": "資料不足", "color": "#F2F2F2"},
        {"key": "C20", "label": "0～20", "color": "#7030A0"},
        {"key": "C40", "label": "20～40", "color": "#FF0000"},
        {"key": "C60", "label": "40～60", "color": "#FFC000"},
        {"key": "C80", "label": "60～80", "color": "#FFFF00"},
        {"key": "FREE", "label": "80 以上", "color": "#00B050"},
    ],
    "freeways": [
        {"id": "1", "name": "國道1號",
         "routes": {"S": route_json(F1_S, a1["S"], "3"), "N": route_json(F1_N, a1["N"], "4")}},
        {"id": "3", "name": "國道3號",
         "routes": {"S": route_json(F3_S, a3["S"], "3"), "N": route_json(F3_N, a3["N"], "4")}},
    ],
    "nodes": n1 + n3,
    "screenlines": SCREENLINES,
    "decorations": {
        # 路線端點的方向標示：箭頭方向 = 行車方向
        "ends": [
            # 國1 右端（北端）：南下往左、北上往右
            ends("1", "right", 1760, 627.0, 679.6, 1775, 1711, 1786, (1783, 541)),
            # 國1 左端（南端）
            ends("1", "left", -420, 163.0, 209.0, -364, -428, -480, (-504, 79)),
            # 國3 右端（北端）
            ends("3", "right", 1650, -204.5, -148.6, 1679, 1615, 1690, (1783, -215)),
            # 國3 左端（南端）
            ends("3", "left", -420, 783.8, 839.8, -377, -441, -492, (-504, 706)),
        ],
        "shields": [
            {"freeway": "1", "x": round(471 + DX, 1), "y": round(232 + DY, 1), "size": 47},
            {"freeway": "3", "x": round(821 + DX, 1), "y": round(69 + DY, 1), "size": 47},
        ],
    },
    "panels": {
        "legend": {"x": 30, "y": 30},
        "times": {"x": 30, "y": 175},
    },
}

# 檢查：每個 RMS 只出現一次；與現場清單比對
rms_ids = []
for nd in network["nodes"]:
    for d, r in nd["ramps"].items():
        rms_ids += r.get("rms", [])
dups = {x for x in rms_ids if rms_ids.count(x) > 1}
if dups:
    raise SystemExit(f"RMS 重複：{dups}")

if len(sys.argv) > 2:
    import unicodedata
    # 來源有時用相容字（例如「北」U+F963），比對前一律 NFKC 正規化
    live = [unicodedata.normalize("NFKC", l.strip()) for l in open(sys.argv[2], encoding="utf-8") if l.strip()]
    missing = sorted(set(live) - set(rms_ids))
    unknown = sorted(set(rms_ids) - set(live))
    print("RMS in config:", len(rms_ids), "live:", len(live))
    print("live but not in config:", missing)
    print("config but not live:", unknown)

with open(sys.argv[1], "w", encoding="utf-8") as f:
    json.dump(network, f, ensure_ascii=False, indent=1)
    f.write("\n")
print("nodes", len(network["nodes"]), "points", sum(len(r["points"]) for fw in network["freeways"] for r in fw["routes"].values()))
