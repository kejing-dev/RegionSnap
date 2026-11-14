#!/usr/bin/env python3
"""Region Snap copy, both languages in one table (design content.copy / content.tone: spoken, short; profile D28).

    python tools/strings.py      -> app/src/main/res/values{,-zh,-en}/strings.xml

values/ is Chinese (the default), values-zh/ the same, values-en/ English (factory rule 51). No company name anywhere.
"""
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

S = [
    ("app_name", "框一下", "Region Snap"),
    ("tab_snap", "取景", "Snap"),
    ("tab_shots", "已截", "Shots"),
    ("tab_settings", "设置", "Settings"),
    # Snap page: status line (design panels.main.pages.snap, content.copy)
    ("st_idle", "看准一块地方，捏住拖一个框", "Look at a spot, pinch and drag a frame"),
    ("st_idle_ctrl", "指着一块地方，按住扳机拖一个框", "Point at a spot, hold the trigger and drag"),
    ("st_drawing", "松手就成框", "Let go and it's a frame"),
    ("st_adjusting", "拖到合适就松手", "Let go when it fits"),
    ("st_framed", "再捏一下，拍", "Pinch again to shoot"),
    ("st_framed_ctrl", "再扣一下扳机，拍", "Pull the trigger again to shoot"),
    ("st_capturing", "正在拍…", "Shooting…"),
    ("st_saved", "拍好了", "Got it"),
    ("st_pinned", "钉好了", "Pinned"),
    ("in_controller", "手柄", "Controller"),
    ("in_eye", "眼睛 + 手", "Eyes + hand"),
    ("in_finger", "手指指向（眼动未开）", "Finger aim (eye tracking off)"),
    ("in_eye_nohand", "眼睛（手未追踪）", "Eyes (hands not tracked)"),
    ("in_head", "头的朝向（拿手柄更准）", "Head aim (controller is better)"),
    ("sf_wall", "贴在墙上", "On the wall"),
    ("sf_table", "贴在桌面", "On the table"),
    ("sf_floor", "贴在地上", "On the floor"),
    ("sf_plane", "贴在平面上", "On a surface"),
    ("sf_float", "浮空 %1$s m", "Floating %1$s m"),
    ("sf_none", "没瞄到", "Not aiming"),
    ("size_none", "还没有框", "No frame yet"),
    ("size_float", "浮空 · 约 %1$s m", "Floating · about %1$s m"),
    ("btn_same", "同上次", "Same as last"),
    ("btn_redraw", "重画", "Redraw"),
    ("btn_shoot", "拍", "Shoot"),
    ("btn_stick", "贴表面", "Stick to surfaces"),
    ("mute", "静音", "Mute"),
    ("unmute", "有声", "Sound on"),
    ("stick_note", "握把 + 摇杆左右：转框", "Grip + stick left / right: turn the frame"),
    ("rot_left", "左转 15°", "Left 15°"),
    ("rot_right", "右转 15°", "Right 15°"),
    ("face_me", "回正", "Face me"),
    # S01 onboarding
    ("onboard_1", "1 看准一块地方", "1 Look at a spot"),
    ("onboard_2", "2 捏住，拖一个框（画个圈也行）", "2 Pinch and drag a frame (or draw a loop)"),
    ("onboard_3", "3 再捏一下，拍", "3 Pinch again to shoot"),
    ("onboard_ctrl", "用手柄：指着，按住扳机拖，再扣一下拍", "Controller: point, hold the trigger, drag, pull again"),
    ("onboard_start", "开始", "Start"),
    # S09 preview
    ("pv_rectify", "拉平", "Straighten"),
    ("pv_save", "存", "Save"),
    ("pv_save_pin", "存并钉在这里", "Save and pin here"),
    ("pv_retake", "重拍", "Retake"),
    ("pv_info_m", "%1$s × %2$s m · %3$d × %4$d px · %5$s", "%1$s × %2$s m · %3$d × %4$d px · %5$s"),
    ("pv_info_float", "浮空 · 约 %1$s m · %2$d × %3$d px", "Floating · about %1$s m · %2$d × %3$d px"),
    ("pv_drag", "拖四个角，对准真的边", "Drag the corners onto the real edges"),
    ("pv_sample", "示例帧（模拟器没有相机）", "Sample frame (no camera on the emulator)"),
    # S16 / S18 cards
    ("cam_perm", "要拍下框里的画面，得允许用相机。只在你按下拍的那一刻取一帧，不上传。", "To shoot what's in the frame I need the camera. One frame, only when you press shoot. Nothing is uploaded."),
    ("cam_allow", "允许", "Allow"),
    ("cam_notnow", "先不拍", "Not now"),
    ("cam_forever", "相机权限关着。去系统设置里打开。", "Camera access is off. Turn it on in system settings."),
    ("cam_settings", "去系统设置", "System settings"),
    ("cam_failed", "这台设备现在拿不到相机画面。框还能画，拍要等相机回来。", "Can't get the camera right now. You can still frame; shooting needs the camera back."),
    ("cam_starting", "相机还在启动，再试一次。", "Camera is still starting. Try once more."),
    ("cam_retry", "重试", "Retry"),
    ("cam_ok", "知道了", "Got it"),
    # Shots
    ("shots_empty", "还没拍过。回到取景页，框一块试试。", "Nothing yet. Go to Snap and frame something."),
    ("shots_count", "%1$d 张 · 钉着 %2$d 张", "Shots: %1$d · Pinned: %2$d"),
    ("shots_back", "全部", "All"),
    ("pin_back", "钉回原地", "Pin back in place"),
    ("pin_front", "钉到面前", "Pin in front"),
    ("unpin", "取下", "Take down"),
    ("delete", "删除", "Delete"),
    ("delete_q", "删掉这张？相册里的也一起删，墙上钉的也会取下", "Delete this? The copy in Photos and the pinned note go too"),
    ("cancel", "取消", "Cancel"),
    ("delete_yes", "删", "Delete"),
    ("album_gone", "相册里已删", "Gone from Photos"),
    ("badge_pinned", "已钉", "Pinned"),
    ("pin_lost", "没找到位置", "Spot not found"),
    # Settings
    ("set_lang", "语言", "Language"),
    ("lang_auto", "跟随系统", "System"),
    ("lang_zh", "中文", "中文"),
    ("lang_en", "English", "English"),
    ("set_res", "拍摄分辨率", "Capture size"),
    ("set_shutter", "快门声", "Shutter sound"),
    ("set_mute", "静音", "Mute"),
    ("set_pinch", "捏合拖动", "Pinch and drag"),
    ("pinch_auto", "自动", "Auto"),
    ("pinch_box", "拉框", "Box"),
    ("pinch_loop", "画圈", "Loop"),
    ("set_stick", "贴表面", "Stick to surfaces"),
    ("set_eye", "眼动没开 · 去系统权限", "Eye tracking off · system settings"),
    ("eye_go", "去设置", "Open"),
    ("set_calib", "校准一次", "Calibrate once"),
    ("calib_done", "已校准", "Calibrated"),
    ("calib_rough", "已校准（粗略）", "Calibrated (rough)"),
    ("calib_clear", "清掉", "Clear"),
    ("recall", "叫回面板", "Bring the panel back"),
    ("save_where", "存在 相册 → Pictures/RegionSnap", "Saved to Photos → Pictures/RegionSnap"),
    ("about", "关于", "About"),
    ("about_ver", "框一下 %1$s", "Region Snap %1$s"),
    ("privacy", "相机画面只在你按下拍的那一刻取一帧，不上传、不联网。", "The camera is read for one frame only when you press shoot. Nothing leaves the headset."),
    ("about_icons", "图标：Material Symbols（Apache-2.0）。音效：CC0 录音。", "Icons: Material Symbols (Apache-2.0). Sounds: CC0 recordings."),
    ("about_close", "返回", "Back"),
    ("quit", "退出应用", "Quit"),
    ("exit_q", "退出框一下？", "Quit Region Snap?"),
    ("exit_yes", "退出", "Quit"),
    # S20 calibrate once
    ("calib_title", "校准一次", "Calibrate once"),
    ("calib_s1", "1  在墙上或桌上放一张纸", "1  Put a sheet of paper on a wall or table"),
    ("calib_s2", "2  框住它，站在 1.5 米以内", "2  Frame it from within 1.5 m"),
    ("calib_s3", "3  拍一张，把四个角拖到纸边上", "3  Shoot, then drag the four corners onto its edges"),
    ("calib_hint", "框住一张纸，拍一张。然后把四个角拖到纸的边上。", "Frame a sheet of paper and shoot. Then drag the four corners onto its edges."),
    ("calib_go", "去取景页框纸", "Frame it on Snap"),
    ("calib_ok", "就这样", "That's it"),
    ("calib_anyway", "先这样", "Use it anyway"),
    ("calib_retry", "这张不够稳。换一张大一点、正一点的纸再试。", "Not steady enough. Try a bigger sheet, more straight on."),
    # hints (one line, R2-1)
    ("h_too_small", "框太小了，再拖大一点", "Too small. Drag it a bit bigger"),
    ("h_eye_fallback", "眼动没开，先用手指指向。系统设置里可以打开眼动权限。", "Eye tracking is off, aim with your finger for now. You can allow it in settings."),
    ("h_hand_lost", "手没追踪到。拿起手柄画框。看着按钮捏一下也许还行。", "Can't see your hands. Pick up a controller to draw. Looking at a button and pinching may still work."),
    ("h_head", "眼睛和手都用不了，先用头的朝向瞄。拿起手柄更准。", "No eyes or hands, aiming with your head for now. A controller works better."),
    ("h_cam_starting", "相机还在启动，再试一次。", "Camera is still starting. Try once more."),
    ("h_low_res", "离近一点再拍会更清楚", "Step in a little for a sharper shot"),
    ("h_anchors_lost", "上次钉的 %1$d 张便签这次没找到位置。已截页里可以钉到面前。", "Couldn't find where %1$d of your notes were. Pin them in front of you from Shots."),
    ("h_pin_not_kept", "这张便签这次关掉就不会留下", "This note won't stay after you close the app"),
    ("h_pins_behind", "你钉的 %1$d 张便签在身后或另一个房间", "Your %1$d pinned notes are behind you or in another room"),
    ("h_pin_limit", "最多钉 20 张。先取下一张。", "20 notes at most. Take one down first."),
    ("h_calib_float", "先把框贴到纸上再拍", "Stick the frame on the paper first"),
    ("h_calib_far", "离近一点，1.5 米以内", "Come closer, within 1.5 m"),
    ("h_calib_small", "框大一点，短边 15 厘米以上", "Make the frame bigger, 15 cm on the short side"),
    ("h_calib_off", "离校准时的距离差得多，角点可能偏一点", "Far from where you calibrated, corners may be a bit off"),
    ("h_saved", "已存到相册 · Pictures/RegionSnap", "Saved to Photos · Pictures/RegionSnap"),
    ("h_pinned", "拍好了。钉在这儿，下次进来它还在。", "Got it. Pinned here, it'll still be here next time."),
    # Stage small panels
    ("look_level", "平视前方", "Look straight ahead"),
    ("look_level_2", "面板马上出来", "The panel is on its way"),
    ("panel_closed", "面板关了。长按扳机或捏合 1 秒，或按 A/X，打开。", "Panel closed. Hold the trigger or a pinch for 1 s, or press A/X, to bring it back."),
    ("open_panel", "打开面板", "Open panel"),
    ("look_left", "← 往左看一点", "← Look a little to the left"),
    ("look_right", "往右看一点 →", "Look a little to the right →"),
    ("turn_left_deg", "← 往左转约 %1$d°", "← Turn about %1$d° to the left"),
    ("turn_right_deg", "往右转约 %1$d° →", "Turn about %1$d° to the right →"),
    ("look_turn_2", "面板开在框旁边，不挡框", "The panel opens beside your frame"),
    ("panel_tucked", "面板先让开了，不挡你取景。要用时点这里。", "The panel stepped aside so it won't block your view. Tap here when you need it."),
    ("move_aside", "让开", "Move aside"),
    ("move_aside_q", "面板挡住要框的地方？", "Panel in the way of your shot?"),
    ("bar_shoot", "拍", "Shoot"),
    ("bar_redraw", "重画", "Redraw"),
    ("bar_left", "左转", "Left"),
    ("bar_right", "右转", "Right"),
    ("bar_face", "回正", "Face me"),
    ("bar_far", "远", "Far"),
    ("bar_near", "近", "Near"),
    ("bar_panel", "面板", "Panel"),
    ("bar_cam_wait", "相机还在启动…", "Camera starting…"),
    ("pin_open", "打开", "Open"),
    ("pin_take", "取下", "Take down"),
    ("pin_here", "移到面前", "Bring here"),
    ("pin_close", "关", "Close"),
]


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "\\'").replace('"', '\\"')


def write(d, idx):
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "strings.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<!-- generated by tools/strings.py: edit there -->\n<resources>\n')
        for row in S:
            f.write('    <string name="%s"%s>%s</string>\n' % (row[0], ' formatted="false"' if "%%" in row[idx] and "%1" not in row[idx] else "", esc(row[idx])))
        f.write("</resources>\n")


def main():
    keys = [r[0] for r in S]
    dup = {k for k in keys if keys.count(k) > 1}
    if dup:
        sys.exit("duplicate keys: %s" % dup)
    res = os.path.join(ROOT, "app", "src", "main", "res")
    write(os.path.join(res, "values"), 1)
    write(os.path.join(res, "values-zh"), 1)
    write(os.path.join(res, "values-en"), 2)
    for row in S:
        if "embodit" in (row[1] + row[2]).lower():
            sys.exit("company name in copy: %s" % row[0])
    print("strings: %d keys" % len(S))


if __name__ == "__main__":
    main()
