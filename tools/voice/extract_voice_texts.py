# -*- coding: utf-8 -*-
"""Extract the 92 voice-clip texts (fa/ps/en) from Android string resources
into voice_texts.json for the TTS regeneration pipeline."""
import re, json, os, html

REPO = "/home/z/my-project/rahyab"
RES = os.path.join(REPO, "app/src/main/res")

# key -> resource name (from NavigationController.instrTitle / alertText / call sites)
RES_MAP = {
    "depart": "nav_depart", "arrive": "nav_arrive", "arrive_farewell": "nav_arrive_farewell",
    "trip_end": "nav_trip_end", "roundabout": "nav_roundabout", "exit_roundabout": "nav_exit_roundabout",
    "exit_1": "nav_exit_1", "exit_2": "nav_exit_2", "exit_3": "nav_exit_3",
    "right": "nav_right", "left": "nav_left",
    "slight_right": "nav_slight_right", "slight_left": "nav_slight_left",
    "sharp_right": "nav_sharp_right", "sharp_left": "nav_sharp_left",
    "uturn": "nav_uturn", "go_right_side": "nav_go_right_side", "go_left_side": "nav_go_left_side",
    "stay_right": "nav_stay_right", "stay_left": "nav_stay_left",
    "fork_right": "nav_fork_right", "fork_left": "nav_fork_left",
    "enter_road": "nav_enter_road", "exit_road": "nav_exit_road",
    "enter_main": "nav_enter_main", "exit_main": "nav_exit_main",
    "enter_alt": "nav_enter_alt", "lane_change": "nav_lane_change",
    "keep_right": "nav_keep_right", "keep_left": "nav_keep_left", "keep_road": "nav_keep_road",
    "route_started": "nav_route_started", "route_changed": "nav_route_changed",
    "faster_found": "nav_faster_found", "gps_lost": "nav_gps_lost", "gps_restored": "nav_gps_restored",
    "approaching": "nav_approaching", "dest_right": "nav_dest_right", "dest_left": "nav_dest_left",
    "dest_500": "nav_dest_500", "dest_200": "nav_dest_200",
    "offroute": "nav_offroute", "rerouted": "nav_rerouted", "continue": "nav_continue",
    "straight": "nav_straight",
    # alerts (NavigationController.alertText)
    "alert_bump": "alert_bump_text", "alert_accident": "alert_accident_text",
    "alert_hazard": "alert_hazard_text",
    "alert_traffic": "alert_traffic_text", "alert_closed": "alert_closed_text",
    "alert_cam": "alert_cam_text", "alert_speed": "alert_speed_text",
    "alert_police": "alert_police_text", "alert_redlight": "alert_redlight_text",
    "alert_slippery": "alert_slippery_text", "alert_narrow": "alert_narrow_text",
    "alert_school": "alert_school_text", "alert_crosswalk": "alert_crosswalk_text",
    "alert_rail": "alert_rail_text", "alert_animal": "alert_animal_text",
    "alert_trafficlight": "alert_trafficlight_text", "alert_limit": "alert_limit_text",
    "alert_steep_down": "alert_steep_down_text", "alert_steep_up": "alert_steep_up_text",
    "alert_toll": "alert_toll_text", "alert_border": "alert_border_text",
    "alert_bridge": "alert_bridge_text", "alert_tunnel": "alert_tunnel_text",
    "slow_down": "alert_slow_down_text",
    "free_start": "free_started",
}

# legacy / state clips that have no live call site — trilingual manual table
MANUAL = {
    "alt_found": {
        "fa": "مسیر جایگزین پیدا شد.",
        "ps": "بدیل لار پیدا شوه.",
        "en": "An alternative route was found.",
    },
    "be_safe": {
        "fa": "سلامت باشید.",
        "ps": "خوندی اوسئ.",
        "en": "Stay safe.",
    },
    "cross1_left": {
        "fa": "در تقاطع بعدی به چپ بچرخید.",
        "ps": "په راتلونکي تقاطع کې کیڼ ته تاو شئ.",
        "en": "At the next intersection, turn left.",
    },
    "cross1_right": {
        "fa": "در تقاطع بعدی به راست بچرخید.",
        "ps": "په راتلونکي تقاطع کې ښي ته تاو شئ.",
        "en": "At the next intersection, turn right.",
    },
    "d40": {
        "fa": "اکنون.",
        "ps": "اوس.",
        "en": "Now.",
    },
    "d200": {
        "fa": "پس از دوصد متر.",
        "ps": "له دوه سوه مترو وروسته.",
        "en": "In two hundred meters.",
    },
    "d500": {
        "fa": "پس از پانصد متر.",
        "ps": "له پنځوسو مترو وروسته.",
        "en": "In five hundred meters.",
    },
    "d1000": {
        "fa": "پس از یک کیلومتر.",
        "ps": "له یو کیلومتره وروسته.",
        "en": "In one kilometer.",
    },
    "dl_done": {
        "fa": "بارگیری کامل شد.",
        "ps": "ډاونلوډ بشپړ شو.",
        "en": "Download completed.",
    },
    "dl_failed": {
        "fa": "بارگیری ناموفق بود.",
        "ps": "ډاونلوډ ناکام شو.",
        "en": "Download failed.",
    },
    "dl_started": {
        "fa": "بارگیری آغاز شد.",
        "ps": "ډاونلوډ پیل شو.",
        "en": "Download started.",
    },
    "limit_60": {
        "fa": "محدودیت سرعت، شصت کیلومتر در ساعت.",
        "ps": "د چټکتیا محدودیت، شپېته کیلومتره په ساعت کې.",
        "en": "Speed limit, sixty kilometers per hour.",
    },
    "loc_acquired": {
        "fa": "موقعیت شما تعیین شد.",
        "ps": "ستاسو موقعیت معلوم شو.",
        "en": "Your location was found.",
    },
    "loc_unknown": {
        "fa": "موقعیت شما معلوم نیست.",
        "ps": "ستاسو موقعیت معلوم نه ده.",
        "en": "Your location is not known.",
    },
    "locating": {
        "fa": "در حال تعیین موقعیت.",
        "ps": "د موقعیت ټاکنه روانه ده.",
        "en": "Locating your position.",
    },
    "map_loading": {
        "fa": "در حال بارگیری نقشه.",
        "ps": "نخشه بارګېږي.",
        "en": "Loading the map.",
    },
    "map_ready": {
        "fa": "نقشه آماده است.",
        "ps": "نخشه چمتو ده.",
        "en": "The map is ready.",
    },
    "no_internet": {
        "fa": "انترنت در دسترس نیست.",
        "ps": "انټرنټ نه ده پیدا کېږي.",
        "en": "No internet connection.",
    },
    "report_saved": {
        "fa": "گزارش شما ثبت شد. سپاس.",
        "ps": "ستاسو راپور ثبت شو. مننه.",
        "en": "Your report was saved. Thank you.",
    },
    "test": {
        "fa": "آزمایش صدای راهیاب.",
        "ps": "د راهیاب غږ ازمویښت.",
        "en": "Rahyab voice test.",
    },
    "thanks_bye": {
        "fa": "سپاس از همراهی شما. به امید دیدار.",
        "ps": "ستاسو د ملګرتیا څخه مننه. بيا به ګورو.",
        "en": "Thank you for riding with us. Goodbye.",
    },
    "using_offline": {
        "fa": "از نقشه آفلاین استفاده می‌شود.",
        "ps": "له آفلاین نخشه څخه کار اخیستل کېږي.",
        "en": "Using the offline map.",
    },
}

TAG_RE = re.compile(r"<[^>]+>")


def parse_res_dir(folder):
    out = {}
    d = os.path.join(RES, folder)
    if not os.path.isdir(d):
        return out
    for fn in os.listdir(d):
        if not fn.endswith(".xml"):
            continue
        xml = open(os.path.join(d, fn), encoding="utf-8").read()
        for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', xml, re.S):
            name, val = m.group(1), m.group(2)
            val = html.unescape(TAG_RE.sub("", val)).strip()
            out[name] = val
    return out


def main():
    fa = parse_res_dir("values")
    ps = parse_res_dir("values-ps")
    en = parse_res_dir("values-en")

    keys = sorted(
        f[:-4] for f in os.listdir(os.path.join(REPO, "app/src/main/assets/voice/dilara"))
        if f.endswith(".ogg") and f != "chime.ogg"
    )
    assert len(keys) == 92, f"expected 92 keys, got {len(keys)}"

    texts = {}
    missing = []
    for k in keys:
        entry = {}
        for lang, res in (("fa", fa), ("ps", ps), ("en", en)):
            if k in MANUAL:
                entry[lang] = MANUAL[k][lang]
            elif k in RES_MAP and RES_MAP[k] in res:
                entry[lang] = res[RES_MAP[k]]
            else:
                entry[lang] = ""
        if any(v == "" for v in entry.values()):
            missing.append((k, entry))
        texts[k] = entry

    if missing:
        print("MISSING:")
        for k, e in missing:
            print(" ", k, e)
        raise SystemExit(1)

    out = os.path.join("/home/z/my-project/scripts", "voice_texts.json")
    json.dump(texts, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("OK — wrote", out, "with", len(texts), "keys × 3 langs")
    for k in ("depart", "arrive", "right", "d200", "alert_school", "slow_down", "thanks_bye"):
        print(k, "→", texts[k])


if __name__ == "__main__":
    main()
