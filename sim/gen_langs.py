#!/usr/bin/env python3
"""Genere les fichiers de langue (>=80 langues MC valides).

- TRADS : traductions reelles (44 cles) ecrites a la main.
- VARIANTS : override court sur une base existante (variantes regionales).
- COPIES : code -> fichier de base (fallback raisonnable, ex. bavarois <- allemand).
- en_ud : Flip automatique d'en_us (placeholders %s preserves).
"""
import json
import os

LANG_DIR = "src/main/resources/assets/bte_railpathtool/lang"
K = "bte_railpathtool"

def t(tool_name, points, undo, confirm, clear, hint, gen, density, snap, purge,
      ghost, style, classic, nature, theme, dark, light, fill, uniform, rand,
      block, rand_hint, pick, height, surface, buried, orient, auto, ns, ew,
      diag, no_target, added, removed, built, nothing, too_big, h1, h2, h3,
      est, length, smooth, gspline):
    """Construit le dictionnaire complet dans l'ordre des cles."""
    vals = [tool_name, points, undo, confirm, clear, hint, gen, density, snap,
            purge, ghost, style, classic, nature, theme, dark, light, fill,
            uniform, rand, block, rand_hint, pick, height, surface, buried,
            orient, auto, ns, ew, diag, no_target, added, removed, built,
            nothing, too_big, h1, h2, h3, est, length, smooth, gspline]
    keys = ["tool.name", "ui.points", "ui.undo", "ui.confirm", "ui.clear",
            "ui.hint", "ui.gen", "ui.density", "ui.ground_snap",
            "ui.purge_corners", "ui.ghost", "ui.style", "ui.style.classic",
            "ui.style.nature", "ui.theme", "ui.theme.dark", "ui.theme.light",
            "ui.fill", "ui.fill.uniform", "ui.fill.random", "ui.fill.block",
            "ui.fill.random_hint", "ui.pick_active", "ui.height",
            "ui.height.surface", "ui.height.buried", "ui.orientation",
            "ui.orient.auto", "ui.orient.ns", "ui.orient.ew", "ui.orient.diag",
            "ui.no_target", "ui.point_added", "ui.point_removed", "ui.built",
            "ui.nothing", "ui.plan_too_big", "ui.help_1", "ui.help_2",
            "ui.help_3", "ui.est_blocks", "ui.length", "ui.smooth_ridges",
            "ui.ghost_spline"]
    return {f"{K}.{k}": v for k, v in zip(keys, vals)}


TRADS = {}

# --- Anglais GB (chemins de fer !) -------------------------------------------------
TRADS["en_gb"] = t(
    "═ BTE Rail", "Points: %s", "Remove last point", "Build", "Clear all",
    "Right click: add point | Delete: remove | Enter: build", "Track",
    "Spline density", "Snap to terrain", "Clean L-corners", "Ghost preview",
    "Rail style", "Classic", "Nature", "Theme", "Dark", "Light",
    "Ground fill", "Uniform", "Random", "Block: %s",
    "Shares (%) of the 5 mixture blocks:", "Axiom active block", "Height",
    "At surface", "Buried", "Orientation", "Auto", "N-S", "E-W", "Diagonal",
    "No block targeted.", "Point %s added.", "Point removed (%s left).",
    "Railway built (%s blocks).",
    "Nothing to build: place at least 2 points.",
    "Plan too large (%s blocks, max %s): shorten the spline.",
    "1. Right-click: place control points along the railway.",
    "2. Check the ghost preview as you wish.",
    "3. Enter: build (Ctrl+Z to undo in Axiom).", "Planned blocks: %s",
    "Spline length: %s blocks", "Smooth bumps (dig tunnel)",
    "Spline outline (path-style)")

# --- Pirate Speak -------------------------------------------------------------------
TRADS["en_pt"] = t(
    "═ BTE Rail", "Points: %s", "Scuttle last point", "Hoist it!", "Abandon ship",
    "Right click: drop point | Delete: scuttle | Enter: hoist", "Plank",
    "Curve tension", "Snag the land", "Swab the corners", "Spirit preview",
    "Rail fashion", "Old salt", "Wildwood", "Colours", "Stormy", "Sunny",
    "Ground ballast", "Steady", "Fate's hand", "Block: %s",
    "Shares (%) o' the 5 ballast blocks:", "Axiom's held block", "Mast height",
    "Deck level", "Under the keel", "Bearin'", "By the stars", "N-S", "E-W",
    "Quartered", "Nary a block in sight.", "Point %s stowed.",
    "Point scuttled (%s remain).", "Rail laid (%s blocks), arr.",
    "Nothin' to hoist: drop at least 2 points.",
    "Chart too vast (%s blocks, max %s): shorten the sailplan.",
    "1. Right-click: mark waypoints along the rail.",
    "2. Eye the spirit preview as ye please.",
    "3. Enter: hoist (Ctrl+Z to un-hoist in Axiom).", "Counted blocks: %s",
    "Voyage length: %s blocks", "Flatten the swells (dig a hold)",
    "Chain outline (path-style)")

# --- LOLCAT --------------------------------------------------------------------------
TRADS["lol_us"] = t(
    "═ BTE Rail", "Pointz: %s", "Noes last point", "BUILDZ", "All gone!",
    "Rite click: point | Delete: noes | Enter: buildz", "Trak",
    "Curvy densitee", "Snap 2 groun", "Cleen cornerz", "Gost preview",
    "Rail stile", "Klassik", "Naturz", "Skins", "Darkz", "Brite",
    "Groun filly", "Samey", "Randomz", "Block: %s",
    "Sharez (%) of teh 5 mix blockz:", "Axiom block in hand", "Hi-ness",
    "On top", "Undr", "Pointy way", "Auto", "N-S", "E-W", "Slanty",
    "No block seen. Halp.", "Point %s added. Yay.",
    "Point gonez (%s left).", "Rail builtz (%s blockz).",
    "Nuffin to buildz: needz 2 pointz at leest.",
    "Plan 2 big (%s blockz, max %s): make spline shorter.",
    "1. Rite-click: put pointz along teh rail.",
    "2. Luk at gost preview if u wantz.",
    "3. Enter: buildz (Ctrl+Z undoes in Axiom).", "Blockz planned: %s",
    "Spline longness: %s blockz", "Smoov bumpz (dig tunnel)",
    "Spline outline (path-style)")

# --- Francais Canada -----------------------------------------------------------------
TRADS["fr_ca"] = t(
    "═ BTE Rail", "Points : %s", "Retirer le dernier point", "Construire",
    "Tout effacer",
    "Clic droit : ajouter un point | Suppr : retirer | Entrée : construire",
    "Voie", "Densité de la spline", "Coller au terrain",
    "Nettoyer les coins en L", "Aperçu fantôme", "Style de rail", "Classique",
    "Nature", "Thème", "Sombre", "Clair", "Remplissage du sol", "Uniforme",
    "Aléatoire", "Bloc : %s", "Parts (%) des 5 blocs du mélange :",
    "Bloc actif d'Axiom", "Hauteur", "En surface", "Enterré", "Orientation",
    "Auto", "N-S", "E-O", "Diagonale", "Aucun bloc visé.",
    "Point %s ajouté.", "Point retiré (%s restants).",
    "Voie construite (%s blocs).",
    "Rien à construire : place au moins 2 points.",
    "Plan trop grand (%s blocs, max %s) : raccourcis la spline.",
    "1. Clic droit : place les points de contrôle le long de la voie.",
    "2. Vérifie l'aperçu fantôme à ta guise.",
    "3. Entrée : construire (Ctrl+Z pour annuler dans Axiom).",
    "Blocs prévus : %s", "Longueur de la spline : %s blocs",
    "Lisser les bosses (creuser un tunnel)", "Contour de la spline (style tracé)")

# --- Espanol Mexico -------------------------------------------------------------------
TRADS["es_mx"] = t(
    "═ BTE Rail", "Puntos: %s", "Quitar el último punto", "Construir",
    "Borrar todo",
    "Clic derecho: agregar punto | Supr: quitar | Enter: construir", "Vía",
    "Densidad de la spline", "Pegar al terreno", "Limpiar esquinas en L",
    "Vista previa fantasma", "Estilo de riel", "Clásico", "Natural", "Tema",
    "Oscuro", "Claro", "Relleno del suelo", "Uniforme", "Aleatorio",
    "Bloque: %s", "Partes (%) de los 5 bloques de la mezcla:",
    "Bloque activo de Axiom", "Altura", "En superficie", "Enterrado",
    "Orientación", "Auto", "N-S", "E-O", "Diagonal", "Ningún bloque apuntado.",
    "Punto %s agregado.", "Punto quitado (quedan %s).",
    "Vía construida (%s bloques).",
    "Nada que construir: coloca al menos 2 puntos.",
    "Plan demasiado grande (%s bloques, máx. %s): acorta la spline.",
    "1. Clic derecho: coloca los puntos de control a lo largo de la vía.",
    "2. Revisa la vista previa fantasma a tu gusto.",
    "3. Enter: construir (Ctrl+Z para deshacer en Axiom).",
    "Bloques previstos: %s", "Longitud de la spline: %s bloques",
    "Suavizar lomadas (cavar túnel)", "Contorno de la spline (estilo trazo)")

# --- Espanol Argentina ----------------------------------------------------------------
TRADS["es_ar"] = t(
    "═ BTE Rail", "Puntos: %s", "Sacar el último punto", "Construir",
    "Borrar todo",
    "Clic derecho: agregar punto | Supr: sacar | Enter: construir", "Vía",
    "Densidad de la spline", "Pegar al terreno", "Limpiar esquinas en L",
    "Vista previa fantasma", "Estilo de riel", "Clásico", "Natural", "Tema",
    "Oscuro", "Claro", "Relleno del suelo", "Uniforme", "Aleatorio",
    "Bloque: %s", "Partes (%) de los 5 bloques de la mezcla:",
    "Bloque activo de Axiom", "Altura", "En superficie", "Enterrado",
    "Orientación", "Auto", "N-S", "E-O", "Diagonal", "Ningún bloque apuntado.",
    "Punto %s agregado.", "Punto eliminado (quedan %s).",
    "Vía construida (%s bloques).",
    "No hay nada para construir: poné al menos 2 puntos.",
    "Plano demasiado grande (%s bloques, máx. %s): acortá la spline.",
    "1. Clic derecho: poné los puntos de control a lo largo de la vía.",
    "2. Mirá la vista previa fantasma como quieras.",
    "3. Enter: construir (Ctrl+Z para deshacer en Axiom).",
    "Bloques previstos: %s", "Longitud de la spline: %s bloques",
    "Suavizar lomas (cavar túnel)", "Contorno de la spline (estilo trazo)")

# --- Portugues Portugal ---------------------------------------------------------------
TRADS["pt_pt"] = t(
    "═ BTE Rail", "Pontos: %s", "Remover último ponto", "Construir",
    "Limpar tudo",
    "Clique direito: adicionar ponto | Delete: remover | Enter: construir",
    "Via", "Densidade da spline", "Aderir ao terreno", "Limpar cantos em L",
    "Pré-visualização fantasma", "Estilo de carril", "Clássico", "Natureza",
    "Tema", "Escuro", "Claro", "Preenchimento do solo", "Uniforme",
    "Aleatório", "Bloco: %s", "Percentagens (%) dos 5 blocos da mistura:",
    "Bloco activo do Axiom", "Altura", "À superfície", "Enterrado",
    "Orientação", "Auto", "N-S", "E-O", "Diagonal", "Nenhum bloco visado.",
    "Ponto %s adicionado.", "Ponto removido (restam %s).",
    "Via construída (%s blocos).",
    "Nada para construir: coloca pelo menos 2 pontos.",
    "Plano demasiado grande (%s blocos, máx. %s): encurta a spline.",
    "1. Clique direito: coloca pontos de controlo ao longo da via.",
    "2. Verifica a pré-visualização fantasma como quiseres.",
    "3. Enter: construir (Ctrl+Z para anular no Axiom).",
    "Blocos previstos: %s", "Comprimento da spline: %s blocos",
    "Suavizar lombas (escavar túnel)", "Contorno da spline (estilo traçado)")

# --- Chinois simplifie ----------------------------------------------------------------
TRADS["zh_cn"] = t(
    "═ BTE 铁路", "点数: %s", "移除上一个点", "建造", "全部清除",
    "右键: 添加点 | Delete: 移除 | 回车: 建造", "轨道", "样条密度",
    "贴合地形", "清理 L 形转角", "幽灵预览", "铁轨样式", "经典", "自然",
    "主题", "深色", "浅色", "地面填充", "统一", "随机", "方块: %s",
    "5 种混合方块的比例(%):", "Axiom 当前方块", "高度", "地表", "掩埋",
    "方向", "自动", "南北", "东西", "斜线", "未指向任何方块。",
    "已添加点 %s。", "已移除点(剩余 %s)。", "铁路已建造(%s 个方块)。",
    "没有可建造的内容: 请至少放置 2 个点。",
    "计划过大(%s 个方块, 上限 %s): 请缩短样条。",
    "1. 右键: 沿铁路放置控制点。", "2. 按需查看幽灵预览。",
    "3. 回车: 建造(在 Axiom 中按 Ctrl+Z 撤销)。", "预计方块: %s",
    "样条长度: %s 个方块", "抚平隆起(挖掘隧道)", "样条轮廓(路径风格)")

# --- Chinois traditionnel --------------------------------------------------------------
TRADS["zh_tw"] = t(
    "═ BTE 鐵路", "點數: %s", "移除上一個點", "建造", "全部清除",
    "右鍵: 新增點 | Delete: 移除 | Enter: 建造", "軌道", "樣條密度",
    "貼合地形", "清理 L 形轉角", "幽靈預覽", "鐵軌樣式", "經典", "自然",
    "主題", "深色", "淺色", "地面填充", "統一", "隨機", "方塊: %s",
    "5 種混合方塊的比例(%):", "Axiom 目前方塊", "高度", "地表", "掩埋",
    "方向", "自動", "南北", "東西", "斜線", "未指向任何方塊。",
    "已新增點 %s。", "已移除點(剩餘 %s)。", "鐵路已建造(%s 個方塊)。",
    "沒有可建造的內容: 請至少放置 2 個點。",
    "計畫過大(%s 個方塊, 上限 %s): 請縮短樣條。",
    "1. 右鍵: 沿鐵路放置控制點。", "2. 依需求查看幽靈預覽。",
    "3. Enter: 建造(在 Axiom 中按 Ctrl+Z 復原)。", "預計方塊: %s",
    "樣條長度: %s 個方塊", "撫平隆起(挖掘隧道)", "樣條輪廓(路徑風格)")

# --- Turc ------------------------------------------------------------------------------
TRADS["tr_tr"] = t(
    "═ BTE Rail", "Nokta: %s", "Son noktayı kaldır", "İnşa et",
    "Hepsini temizle",
    "Sağ tık: nokta ekle | Delete: kaldır | Enter: inşa et", "Hat",
    "Eğri yoğunluğu", "Araziye oturt", "L köşeleri temizle", "Hayalet önizleme",
    "Ray stili", "Klasik", "Doğal", "Tema", "Koyu", "Açık", "Zemin dolgusu",
    "Düzgün", "Rastgele", "Blok: %s", "5 karışım bloğunun payları (%):",
    "Axiom etkin bloğu", "Yükseklik", "Yüzeyde", "Gömülü", "Yönelim",
    "Otomatik", "K-G", "D-B", "Çapraz", "Hiçbir blok hedeflenmedi.",
    "Nokta %s eklendi.", "Nokta kaldırıldı (%s kaldı).",
    "Demiryolu inşa edildi (%s blok).",
    "İnşa edilecek bir şey yok: en az 2 nokta koy.",
    "Plan çok büyük (%s blok, en çok %s): eğriyi kısalt.",
    "1. Sağ tık: demiryolu boyunca kontrol noktaları koy.",
    "2. Hayalet önizlemeyi istediğin gibi kontrol et.",
    "3. Enter: inşa et (Axiom'da geri almak için Ctrl+Z).",
    "Planlanan bloklar: %s", "Eğri uzunluğu: %s blok",
    "Tümsekleri düzle (tünel kaz)", "Eğri dış çizgisi (yol tarzı)")

# --- Vietnamien ------------------------------------------------------------------------
TRADS["vi_vn"] = t(
    "═ BTE Rail", "Số điểm: %s", "Xoá điểm cuối", "Xây dựng", "Xoá hết",
    "Chuột phải: thêm điểm | Delete: xoá | Enter: xây", "Tuyến",
    "Mật độ spline", "Bám theo địa hình", "Dọn góc chữ L", "Xem trước mờ",
    "Kiểu đường ray", "Cổ điển", "Tự nhiên", "Chủ đề", "Tối", "Sáng",
    "Lấp mặt đất", "Đồng nhất", "Ngẫu nhiên", "Khối: %s",
    "Tỉ lệ (%) của 5 khối hỗn hợp:", "Khối đang chọn của Axiom", "Độ cao",
    "Mặt đất", "Chôn sâu", "Hướng", "Tự động", "B-N", "Đ-T", "Chéo",
    "Không nhắm vào khối nào.", "Đã thêm điểm %s.",
    "Đã xoá điểm (còn %s).", "Đã xây đường sắt (%s khối).",
    "Không có gì để xây: đặt ít nhất 2 điểm.",
    "Kế hoạch quá lớn (%s khối, tối đa %s): hãy rút ngắn spline.",
    "1. Chuột phải: đặt các điểm điều khiển dọc tuyến.",
    "2. Kiểm tra bản xem trước mờ tuỳ ý.",
    "3. Enter: xây (Ctrl+Z để hoàn tác trong Axiom).", "Khối dự kiến: %s",
    "Chiều dài spline: %s khối", "Làm phẳng chỗ gồ (đào hầm)",
    "Đường viền spline (kiểu path)")

# --- Thai --------------------------------------------------------------------------------
TRADS["th_th"] = t(
    "═ BTE Rail", "จุด: %s", "ลบจุดล่าสุด", "สร้าง", "ล้างทั้งหมด",
    "คลิกขวา: เพิ่มจุด | Delete: ลบ | Enter: สร้าง", "เส้นทาง",
    "ความหนาแน่นสปлайน์", "เกาะตามภูมิประเทศ", "เก็บมุมตัว L", "ตัวอย่างโปร่ง",
    "สไตล์ราง", "คลาสสิก", "ธรรมชาติ", "ธีม", "มืด", "สว่าง", "เติมพื้นดิน",
    "เหมือนกัน", "สุ่ม", "บล็อก: %s", "สัดส่วน (%) ของบล็อกผสม 5 ชนิด:",
    "บล็อกที่ใช้งานของ Axiom", "ความสูง", "ระดับผิวดิน", "ฝังดิน", "ทิศทาง",
    "อัตโนมัติ", "เหนือ-ใต้", "ตะวันออก-ตะวันตก", "เฉียง", "ไม่ได้เล็งบล็อกใด",
    "เพิ่มจุด %s แล้ว", "ลบจุดแล้ว (เหลือ %s)", "สร้างทางรถไฟแล้ว (%s บล็อก)",
    "ไม่มีอะไรให้สร้าง: วางอย่างน้อย 2 จุด",
    "แผนใหญ่เกินไป (%s บล็อก สูงสุด %s): ลดความยาวสปлайน์",
    "1. คลิกขวา: วางจุดควบคุมตามแนวทางรถไฟ",
    "2. ตรวจสอบตัวอย่างโปร่งได้ตามต้องการ",
    "3. Enter: สร้าง (Ctrl+Z เพื่อย้อนกลับใน Axiom)", "บล็อกที่วางแผน: %s",
    "ความยาวสปлайน์: %s บล็อก", "เกลี่ยเนิน (ขุดอุโมงค์)",
    "เส้นโครงสปлайน์ (สไตล์เส้นทาง)")

# --- Persan -------------------------------------------------------------------------------
TRADS["fa_ir"] = t(
    "═ BTE Rail", "نقاط: %s", "حذف آخرین نقطه", "ساختن", "پاک‌سازی همه",
    "کلیک راست: افزودن نقطه | Delete: حذف | Enter: ساخت", "مسیر",
    "چگالی اسپلاین", "چسبیدن به زمین", "تمیزکردن گوشه‌های L", "پیش‌نمایش شبح",
    "سبک ریل", "کلاسیک", "طبیعی", "پوسته", "تیره", "روشن", "پرکردن زمین",
    "یکنواخت", "تصادفی", "بلوک: %s", "سهم (%) ۵ بلوک مخلوط:",
    "بلوک فعال آکسیوم", "ارتفاع", "روی سطح", "مدفون", "جهت", "خودکار",
    "شمال-جنوب", "شرق-غرب", "مورب", "هیچ بلوکی هدف نیست.",
    "نقطه %s افزوده شد.", "نقطه حذف شد (%s باقی).", "ریل ساخته شد (%s بلوک).",
    "چیزی برای ساخت نیست: دست‌کم ۲ نقطه بگذار.",
    "طرح خیلی بزرگ است (%s بلوک، حداکثر %s): اسپلاین را کوتاه کن.",
    "۱. کلیک راست: نقاط کنترل را در امتداد ریل بگذار.",
    "۲. پیش‌نمایش شبح را دلخواه بررسی کن.",
    "۳. Enter: ساخت (Ctrl+Z برای برگشت در Axiom).", "بلوک‌های برنامه: %s",
    "طول اسپلاین: %s بلوک", "صاف‌کردن برآمدگی‌ها (کندن تونل)",
    "خط کلی اسپلاین (به سبک مسیر)")

# --- Esperanto -----------------------------------------------------------------------------
TRADS["eo_uy"] = t(
    "═ BTE Rail", "Punktoj: %s", "Forigi lastan punkton", "Konstrui",
    "Forviŝi ĉion",
    "Dekstra klako: aldoni punkton | Delete: forigi | Enter: konstrui",
    "Trako", "Spline-denso", "Alglui al tereno", "Purigi L-angulojn",
    "Fantoma antaŭvido", "Rela stilo", "Klasika", "Natura", "Etoso", "Malhela",
    "Hela", "Grunda plenigo", "Unuforma", "Hazarda", "Bloko: %s",
    "Kvicoj (%) de la 5 miksaĵaj blokoj:", "Aktiva bloko de Axiom", "Alteco",
    "Surfaca", "Enterigita", "Orientiĝo", "Aŭtomata", "N-S", "O-U", "Diagonalo",
    "Neniu bloko celita.", "Punkto %s aldonita.", "Punkto forigita (restas %s).",
    "Fervojo konstruita (%s blokoj).",
    "Nenio konstruenda: metu almenaŭ 2 punktojn.",
    "Plano tro granda (%s blokoj, maks. %s): mallongigu la splajnon.",
    "1. Dekstra klako: metu kontrolpunktojn laŭ la fervojo.",
    "2. Kontrolu la fantoman antaŭvidon laŭvole.",
    "3. Enter: konstrui (Ctrl+Z por malfari en Axiom).",
    "Planitaj blokoj: %s", "Longeco de splajno: %s blokoj",
    "Glatigi montetojn (fosi tunelon)", "Splajna konturo (padostila)")

# --- Latin -----------------------------------------------------------------------------------
TRADS["la_la"] = t(
    "═ BTE Rail", "Puncta: %s", "Punctum ultimum tollere", "Aedificare",
    "Omnia delere",
    "Dexter click: punctum addere | Delete: tollere | Enter: aedificare",
    "Trames", "Densitas spline", "Terrae adhaerere", "Angulos L purgare",
    "Praevisio umbratica", "Genus ferriviae", "Classicum", "Naturale",
    "Thema", "Obscurum", "Lucidum", "Repletio soli", "Aequale", "Fortuitum",
    "Quadratum: %s", "Partes (%) quinque quadratorum mixti:",
    "Quadratum activum Axiom", "Altitudo", "In superficie", "Defossum",
    "Directio", "Automata", "S-M", "O-O", "Diagonalis", "Nullum quadratum signatum.",
    "Punctum %s additum.", "Punctum sublatum (%s restant).",
    "Ferrivia aedificata (%s quadrata).",
    "Nihil aedificandum: saltem duo puncta pone.",
    "Consilium nimis magnum (%s quadrata, max %s): splinem brevia.",
    "1. Dexter click: puncta moderamina per ferriviam pone.",
    "2. Praevisionem umbraticam ad libitum specta.",
    "3. Enter: aedifica (Ctrl+Z ad revocandum in Axiom).",
    "Quadrata destinata: %s", "Longitudo spline: %s quadrata",
    "Tumulos complanare (cuniculum fodere)", "Linea spline (more itineris)")

# --- Afrikaans --------------------------------------------------------------------------------
TRADS["af_za"] = t(
    "═ BTE Rail", "Punte: %s", "Verwyder laaste punt", "Bou", "Vee alles uit",
    "Regsklik: voeg punt by | Delete: verwyder | Enter: bou", "Spoor",
    "Spline-digtheid", "Kliek aan terrein", "Maak L-hoeke skoon",
    "Spook-voorskou", "Spoorstyl", "Klassiek", "Natuur", "Tema", "Donker",
    "Lig", "Grondvulling", "Eenvormig", "Ewekansig", "Blok: %s",
    "Aandele (%) van die 5 mengselblokke:", "Axiom aktiewe blok", "Hoogte",
    "Op oppervlak", "Begrawe", "Oriëntasie", "Outo", "N-S", "O-W", "Diagonaal",
    "Geen blok geteiken nie.", "Punt %s bygevoeg.",
    "Punt verwyder (%s oor).", "Spoor gebou (%s blokke).",
    "Niks om te bou nie: plaas ten minste 2 punte.",
    "Plan te groot (%s blokke, maks %s): maak die spline korter.",
    "1. Regsklik: plaas beheerpunte langs die spoor.",
    "2. Kyk na die spook-voorskou soos jy wil.",
    "3. Enter: bou (Ctrl+Z om ongedaan te maak in Axiom).",
    "Beplande blokke: %s", "Spline-lengte: %s blokke",
    "Stryk knoppies glad (grawe tonnel)", "Spline-omlyning (pad-styl)")

# --- Ukrainien ----------------------------------------------------------------------------------
TRADS["uk_ua"] = t(
    "═ BTE Rail", "Точки: %s", "Видалити останню точку", "Побудувати",
    "Очистити все",
    "ПКМ: додати точку | Delete: видалити | Enter: побудувати", "Колія",
    "Щільність сплайна", "Прив'язка до ґрунту", "Чистити Г-подібні роги",
    "Примарний огляд", "Стиль рейок", "Класичний", "Природний", "Тема",
    "Темна", "Світла", "Засипка ґрунту", "Однорідна", "Випадкова",
    "Блок: %s", "Частки (%) 5 блоків суміші:", "Активний блок Axiom", "Висота",
    "На поверхні", "Закопано", "Орієнтація", "Авто", "Пн-Пд", "Сх-Зх",
    "Діагональ", "Жодного блока не вибрано.", "Точку %s додано.",
    "Точку видалено (залишилось %s).", "Колію побудовано (%s блоків).",
    "Нічого будувати: постав щонайменше 2 точки.",
    "План завеликий (%s блоків, макс. %s): скороти сплайн.",
    "1. ПКМ: розстав контрольні точки вздовж колії.",
    "2. Перевір примарний огляд на смак.",
    "3. Enter: побудувати (Ctrl+Z — скасувати в Axiom).",
    "Заплановано блоків: %s", "Довжина сплайна: %s блоків",
    "Згладити горби (копати тунель)", "Контур сплайна (стиль шляху)")

# --- Bielorusse ---------------------------------------------------------------------------------
TRADS["be_by"] = t(
    "═ BTE Rail", "Колькі: %s", "Выдаліць апошнюю кропку", "Пабудаваць",
    "Ачысціць усё",
    "ПКМ: дадаць кропку | Delete: выдаліць | Enter: пабудаваць", "Шлях",
    "Шчыльнасць сплайна", "Прывязка да зямлі", "Чысціць Г-вуглы",
    "Успень прагляду", "Стыль рэек", "Класічны", "Прыродны", "Тэма",
    "Цёмная", "Светлая", "Засыпка зямлі", "Аднародная", "Выпадковая",
    "Блок: %s", "Долі (%) 5 блокаў сумесі:", "Актыўны блок Axiom", "Вышыня",
    "На паверхні", "Закапана", "Арыентацыя", "Аўта", "Пн-Пд", "Ус-Зх",
    "Дыяганаль", "Блок не абраны.", "Кропку %s дададзена.",
    "Кропку выдалена (засталося %s).", "Шлях пабудаваны (%s блокаў).",
    "Няма чаго будаваць: пастаў не менш за 2 кропкі.",
    "План завялікі (%s блокаў, макс. %s): скараці сплайн.",
    "1. ПКМ: расстаў кантрольныя кропкі ўздоўж шляху.",
    "2. Правер успень прагляду як трэба.",
    "3. Enter: пабудаваць (Ctrl+Z — адмяніць у Axiom).",
    "Запланавана блокаў: %s", "Даўжыня сплайна: %s блокаў",
    "Згладзіць горбы (капаць тунэль)", "Контур сплайна (стыль шляху)")

# --- Early Modern English (enws) ----------------------------------------------------------------
TRADS["enws"] = t(
    "═ BTE Rail", "Pointes: %s", "Remooue laſt pointe", "Builde", "Cleare all",
    "Right clicke: adde pointe | Delete: remooue | Enter: builde", "Tracke",
    "Splyne denſitie", "Snappe to terrain", "Cleanſe L-corners",
    "Ghoſtly preuiew", "Raile ſtyle", "Claſſick", "Nature", "Theame", "Darke",
    "Light", "Ground fill", "Vniforme", "Randome", "Blocke: %s",
    "Shares (%) of the 5 mixture blockes:", "Axiom aktiue blocke", "Height",
    "At ſurfaſe", "Buried", "Orientacion", "Auto", "N-S", "E-W", "Diagonal",
    "No blocke targeted.", "Pointe %s added.", "Pointe remooued (%s left).",
    "Rayle built (%s blockes).",
    "Nought to builde: place at leaſt 2 pointes.",
    "Plann too large (%s blockes, max %s): ſhorten ye ſplyne.",
    "1. Right-clicke: place cõtrol pointes along ye rayle.",
    "2. Checke ye ghoſtly preuiew as thou wilt.",
    "3. Enter: builde (Ctrl+Z to vndoe in Axiom).", "Planned blockes: %s",
    "Splyne length: %s blockes", "Smooth bumpyngs (dig tunnel)",
    "Splyne outline (path-ſtyle)")

# --- Anglish (enp) ------------------------------------------------------------------------------
TRADS["enp"] = t(
    "═ BTE Rail", "Dots: %s", "Offdo last dot", "Build", "Wipe all",
    "Right click: add dot | Delete: offdo | Enter: build", "Track",
    "Bend thickn", "Klip to earth", "Mak L-horns clean", "Ghost foresight",
    "Rail kind", "Oldwise", "Wild", "Look", "Dark", "Light", "Ground fill",
    "Alike", "Happen", "Block: %s", "Shares (%) of the 5 ming blocks:",
    "Axiom work block", "Hith", "On top", "Underground", "Heading", "Self",
    "N-S", "E-W", "Slant", "No block sighted.", "Dot %s added.",
    "Dot offdone (%s left).", "Rail built (%s blocks).",
    "Nothing to build: set at least 2 dots.",
    "Frame too big (%s blocks, most %s): mak the bend shorter.",
    "1. Right-click: set wield dots along the rail.",
    "2. Look at the ghost foresight as thou wilt.",
    "3. Enter: build (Ctrl+Z to undo in Axiom).", "Meant blocks: %s",
    "Bend length: %s blocks", "Smoothen knolls (delve tunnel)",
    "Bend outline (path-like)")

# =============================================================================================
# VARIANTES : petites retouches sur un fichier existant (base lue depuis LANG_DIR)
# =============================================================================================
VARIANTS = {  # code -> (base, {"cle courte": valeur})
    "en_au": ("en_gb.json", {"ui.clear": "Clear the lot", "ui.confirm": "Build!"}),
    "en_ca": ("en_gb.json", {}),
    "en_nz": ("en_gb.json", {}),
    "es_cl": ("es_es.json", {"ui.nothing": "Nada que construir: pon al menos 2 puntos."}),
    "es_ec": ("es_es.json", {}),
    "es_uy": ("es_es.json", {"ui.nothing": "No hay nada para construir: poné al menos 2 puntos."}),
    "es_ve": ("es_es.json", {}),
    "esan": ("es_es.json", {"ui.confirm": "Contruí", "ui.clear": "Borrarlo tó"}),
    "de_at": ("de_de.json", {}),
    "de_ch": ("de_de.json", {}),
    "zh_hk": ("zh_tw.json", {}),
    "nl_be": ("nl_nl.json", {}),
    "es_ar": ("es_ar.json", {}),
}

# =============================================================================================
# COPIES : code -> base (fallback raisonnable, fichier existant ou genere ci-dessus)
# =============================================================================================
COPIES = {
    "bar": "de_de.json", "ksh": "de_de.json", "nds_de": "de_de.json",
    "sxu": "de_de.json", "lb_lu": "de_de.json", "fra_de": "fr_fr.json",
    "fy_nl": "nl_nl.json", "li_li": "nl_nl.json", "af_za_base_skip": None,
    "br_fr": "fr_fr.json", "oc_fr": "fr_fr.json",
    "val_es": "ca_es.json", "ast_es": "es_es.json", "gl_es": "es_es.json",
    "eu_es": "es_es.json", "ca_es_skip": None,
    "lzh": "zh_tw.json", "is_is": "da_dk.json", "fo_fo": "da_dk.json",
    "nn_no": "nb_no.json", "se_no": "nb_no.json", "hn_no": "nb_no.json",
    "ovd": "sv_se.json", "mk_mk": "bg_bg.json", "bs_ba": "hr_hr.json",
    "isv": "ru_ru.json", "be_tarask": "be_by.json", "ry_ua": "uk_ua.json",
    "tt_ru": "ru_ru.json", "ba_ru": "ru_ru.json", "sah_sah": "ru_ru.json",
    "kk_kz": "ru_ru.json", "ky_kg": "ru_ru.json", "ka_ge": "en_us.json",
    "hy_am": "en_us.json", "az_az": "tr_tr.json", "sq_al": "en_us.json",
    "mt_mt": "en_gb.json", "kw_gb": "en_gb.json", "cy_gb": "en_gb.json",
    "ga_ie": "en_gb.json", "gd_gb": "en_gb.json", "haw_us": "en_us.json",
    "yo_ng": "en_us.json", "ig_ng": "en_us.json", "so_so": "en_us.json",
    "lo_la": "th_th.json", "ta_in": "en_us.json", "kn_in": "en_us.json",
    "mn_mn": "ru_ru.json", "fil_ph": "en_us.json", "nah": "es_mx.json",
    "tzo_mx": "es_mx.json", "lmo": "it_it.json", "vec_it": "it_it.json",
    "fur_it": "it_it.json", "jbo_en": "en_us.json", "qya_aa": "en_us.json",
    "tlh_aa": "en_us.json", "tok": "en_us.json", "io_en": "en_us.json",
    "eo_skip": None, "rpr": "fr_fr.json", "pls": "pl_pl.json",
    "szl": "pl_pl.json", "vp_vl": "en_us.json", "fo_skip": None,
}

# =============================================================================================
# en_ud : chaine inversee + table upside-down, %s preserves
# =============================================================================================
FLIP = {
    "a": "ɐ", "b": "q", "c": "ɔ", "d": "p", "e": "ǝ", "f": "ɟ",
    "g": "ƃ", "h": "ɥ", "i": "ᴉ", "j": "ɾ", "k": "ʞ", "l": "l",
    "m": "ɯ", "n": "u", "o": "o", "p": "d", "q": "b", "r": "ɹ",
    "s": "s", "t": "ʇ", "u": "n", "v": "ʌ", "w": "ʍ", "x": "x",
    "y": "ʎ", "z": "z",
    "A": "∀", "B": "q", "C": "Ɔ", "D": "p", "E": "Ǝ", "F": "Ⅎ",
    "G": "פ", "H": "H", "I": "I", "J": "ſ", "K": "ʞ", "L": "˥",
    "M": "W", "N": "N", "O": "O", "P": "Ԁ", "Q": "Ό", "R": "ᴚ",
    "S": "S", "T": "⊥", "U": "∩", "V": "Λ", "W": "M", "X": "X",
    "Y": "⅄", "Z": "Z",
    "0": "0", "1": "Ɩ", "2": "ᄅ", "3": "Ɛ", "4": "ㄣ", "5": "ϛ",
    "6": "9", "7": "ㄥ", "8": "8", "9": "6",
    ",": "'", ".": "˙", "'": ",", '"': "„", "(": ")", ")": "(",
    "[": "]", "]": "[", "{": "}", "}": "{", "!": "¡", "?": "¿",
    ":": ":", ";": "؛", "-": "-", "|": "|", "%": "%", "═": "═",
}


def flip_ud(s):
    out = []
    parts = s.split("%s")
    for part in reversed(parts):
        out.append("".join(FLIP.get(ch, ch) for ch in reversed(part)))
    return "%s".join(out)


# Cles supplementaires v2 (par defaut: anglais partout)
EXTRA = {
    "ui.adaptive": "Adaptive sampling (curvature)",
    "ui.presets": "Presets",
    "ui.preset_load": "Load",
    "ui.preset_save": "Save",
    "ui.debug": "Debug (timings)",
    "ui.simplify": "Simplify points",
    "ui.disconnected": "Warning: %s track segment(s) disconnected.",
}
EXTRA_TRADS = {
    "fr_fr.json": {"ui.adaptive": "Échantillonnage adaptatif (courbure)",
        "ui.presets": "Présets", "ui.preset_load": "Charger",
        "ui.preset_save": "Sauver", "ui.debug": "Debug (temps)",
        "ui.simplify": "Épurer les points",
        "ui.disconnected": "Attention : %s morceau(x) de voie déconnectés."},
    "fr_ca.json": {"ui.adaptive": "Échantillonnage adaptatif (courbure)",
        "ui.presets": "Présets", "ui.preset_load": "Charger",
        "ui.preset_save": "Sauver", "ui.debug": "Debug (temps)",
        "ui.simplify": "Épurer les points",
        "ui.disconnected": "Attention : %s morceau(x) de voie déconnectés."},
    "de_de.json": {"ui.adaptive": "Adaptive Abtastung (Krümmung)",
        "ui.presets": "Presets", "ui.preset_load": "Laden",
        "ui.preset_save": "Speichern", "ui.debug": "Debug (Zeiten)",
        "ui.simplify": "Punkte vereinfachen",
        "ui.disconnected": "Warnung: %s Gleisabschnitt(e) getrennt."},
    "es_es.json": {"ui.adaptive": "Muestreo adaptativo (curvatura)",
        "ui.presets": "Preajustes", "ui.preset_load": "Cargar",
        "ui.preset_save": "Guardar", "ui.debug": "Depuración (tiempos)",
        "ui.simplify": "Simplificar puntos",
        "ui.disconnected": "Aviso: %s tramo(s) de vía desconectados."},
    "es_mx.json": {"ui.adaptive": "Muestreo adaptativo (curvatura)",
        "ui.presets": "Preajustes", "ui.preset_load": "Cargar",
        "ui.preset_save": "Guardar", "ui.debug": "Depuración (tiempos)",
        "ui.simplify": "Simplificar puntos",
        "ui.disconnected": "Aviso: %s tramo(s) de vía desconectados."},
    "pt_br.json": {"ui.adaptive": "Amostragem adaptativa (curvatura)",
        "ui.presets": "Predefinições", "ui.preset_load": "Carregar",
        "ui.preset_save": "Salvar", "ui.debug": "Debug (tempos)",
        "ui.simplify": "Simplificar pontos",
        "ui.disconnected": "Aviso: %s trecho(s) de via desconectados."},
    "pt_pt.json": {"ui.adaptive": "Amostragem adaptativa (curvatura)",
        "ui.presets": "Predefinições", "ui.preset_load": "Carregar",
        "ui.preset_save": "Guardar", "ui.debug": "Debug (tempos)",
        "ui.simplify": "Simplificar pontos",
        "ui.disconnected": "Aviso: %s troço(s) de via desligados."},
    "it_it.json": {"ui.adaptive": "Campionamento adattivo (curvatura)",
        "ui.presets": "Preset", "ui.preset_load": "Carica",
        "ui.preset_save": "Salva", "ui.debug": "Debug (tempi)",
        "ui.simplify": "Semplifica punti",
        "ui.disconnected": "Attenzione: %s tratta/e scollegata/e."},
    "ru_ru.json": {"ui.adaptive": "Адаптивная выборка (кривизна)",
        "ui.presets": "Пресеты", "ui.preset_load": "Загрузить",
        "ui.preset_save": "Сохранить", "ui.debug": "Отладка (время)",
        "ui.simplify": "Упростить точки",
        "ui.disconnected": "Внимание: %s участка пути отсоединены."},
    "uk_ua.json": {"ui.adaptive": "Адаптивна вибірка (кривина)",
        "ui.presets": "Пресети", "ui.preset_load": "Завантажити",
        "ui.preset_save": "Зберегти", "ui.debug": "Налагодження (час)",
        "ui.simplify": "Спростити точки",
        "ui.disconnected": "Увага: %s ділянки колії від'єднано."},
    "zh_cn.json": {"ui.adaptive": "自适应采样(曲率)", "ui.presets": "预设",
        "ui.preset_load": "读取", "ui.preset_save": "保存",
        "ui.debug": "调试(耗时)", "ui.simplify": "简化点数",
        "ui.disconnected": "警告: %s 段轨道未连通。"},
    "zh_tw.json": {"ui.adaptive": "自適應取樣(曲率)", "ui.presets": "預設",
        "ui.preset_load": "讀取", "ui.preset_save": "儲存",
        "ui.debug": "除錯(耗時)", "ui.simplify": "簡化點數",
        "ui.disconnected": "警告: %s 段軌道未連通。"},
    "ja_jp.json": {"ui.adaptive": "適応サンプリング(曲率)",
        "ui.presets": "プリセット", "ui.preset_load": "読み込み",
        "ui.preset_save": "保存", "ui.debug": "デバッグ(時間)",
        "ui.simplify": "ポイントを簡素化",
        "ui.disconnected": "警告: 線路が %s 箇所分断されています。"},
    "ko_kr.json": {"ui.adaptive": "적응형 샘플링(곡률)", "ui.presets": "프리셋",
        "ui.preset_load": "불러오기", "ui.preset_save": "저장",
        "ui.debug": "디버그(시간)", "ui.simplify": "포인트 단순화",
        "ui.disconnected": "경고: 선로 %s 구간이 끊어져 있습니다."},
    "nl_nl.json": {"ui.adaptive": "Adaptieve bemonstering (kromming)",
        "ui.presets": "Presets", "ui.preset_load": "Laden",
        "ui.preset_save": "Opslaan", "ui.debug": "Debug (tijden)",
        "ui.simplify": "Punten vereenvoudigen",
        "ui.disconnected": "Let op: %s spoorgedeelte(n) losgekoppeld."},
    "pl_pl.json": {"ui.adaptive": "Próbkowanie adaptacyjne (krzywizna)",
        "ui.presets": "Presety", "ui.preset_load": "Wczytaj",
        "ui.preset_save": "Zapisz", "ui.debug": "Debug (czasy)",
        "ui.simplify": "Uprość punkty",
        "ui.disconnected": "Uwaga: %s odcinek/i toru odłączony/ch."},
    "tr_tr.json": {"ui.adaptive": "Uyarlanır örnekleme (eğrilik)",
        "ui.presets": "Ön ayarlar", "ui.preset_load": "Yükle",
        "ui.preset_save": "Kaydet", "ui.debug": "Hata ayıklama (süre)",
        "ui.simplify": "Noktaları sadeleştir",
        "ui.disconnected": "Uyarı: %s hat parçası bağlantısız."},
    "sv_se.json": {"ui.adaptive": "Adaptiv sampling (kurvatur)",
        "ui.presets": "Förinställningar", "ui.preset_load": "Ladda",
        "ui.preset_save": "Spara", "ui.debug": "Debug (tider)",
        "ui.simplify": "Förenkla punkter",
        "ui.disconnected": "Varning: %s spårdel(ar) frånkopplade."},
    "la_la.json": {"ui.adaptive": "Specimina adaptiva (curvatura)",
        "ui.presets": "Praefixa", "ui.preset_load": "Legere",
        "ui.preset_save": "Servare", "ui.debug": "Debug (tempora)",
        "ui.simplify": "Puncta purgare",
        "ui.disconnected": "Monitio: %s segmenta traminis disiuncta."},
    "eo_uy.json": {"ui.adaptive": "Adapta specimeno (kurbeco)",
        "ui.presets": "Antaŭagordoj", "ui.preset_load": "Ŝargi",
        "ui.preset_save": "Konservi", "ui.debug": "Sencimigo (tempoj)",
        "ui.simplify": "Simpligi punktojn",
        "ui.disconnected": "Atentu: %s traka(j) parto(j) malkonektitaj."},
    "lol_us.json": {"ui.adaptive": "Smart curvy sampling", "ui.presets": "Presez",
        "ui.preset_load": "Gimme", "ui.preset_save": "Keepz",
        "ui.debug": "Debug (tiemz)", "ui.simplify": "Les pointz",
        "ui.disconnected": "Oh noes: %s trak bitz not joined."},
    "en_pt.json": {"ui.adaptive": "Crafty curve sampling", "ui.presets": "Presets",
        "ui.preset_load": "Ahoy", "ui.preset_save": "Stow",
        "ui.debug": "Ship's log (tides)", "ui.simplify": "Trim points",
        "ui.disconnected": "Batten down: %s rail piece(s) adrift."},
}


def apply_extra(path, base_name):
    import json as _json
    try:
        data = _json.load(open(path, encoding="utf-8"))
    except Exception:
        return
    for k, v in EXTRA.items():
        data.setdefault(f"{K}.{k}", v)
    for k, v in EXTRA_TRADS.get(base_name, {}).items():
        data[f"{K}.{k}"] = v
    with open(path, "w", encoding="utf-8") as f:
        _json.dump(data, f, ensure_ascii=False, indent=2)


def main():
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    written = []
    en = json.load(open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8"))

    for code, data in TRADS.items():
        path = os.path.join(LANG_DIR, f"{code}.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        written.append(code)

    for code, (base, ov) in VARIANTS.items():
        bpath = os.path.join(LANG_DIR, base)
        if not os.path.exists(bpath):
            continue
        data = json.load(open(bpath, encoding="utf-8"))
        for k, v in ov.items():
            data[f"{K}.{k}"] = v
        path = os.path.join(LANG_DIR, f"{code}.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        written.append(code)

    for code, base in COPIES.items():
        if base is None:
            continue
        bpath = os.path.join(LANG_DIR, base)
        if not os.path.exists(bpath):
            continue
        data = json.load(open(bpath, encoding="utf-8"))
        path = os.path.join(LANG_DIR, f"{code}.json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        written.append(code)

    ud = {k: (v if k.endswith("tool.name") else flip_ud(v)) for k, v in en.items()}
    # Libelle principal lisible
    ud[f"{K}.tool.name"] = "═ BTE Rail"
    with open(os.path.join(LANG_DIR, "en_ud.json"), "w", encoding="utf-8") as f:
        json.dump(ud, f, ensure_ascii=False, indent=2)
    written.append("en_ud")

    for fn in sorted(os.listdir(LANG_DIR)):
        if fn.endswith(".json"):
            apply_extra(os.path.join(LANG_DIR, fn), fn)
    total = len([fn for fn in os.listdir(LANG_DIR) if fn.endswith(".json")])
    print(f"generes/maj {len(written)} fichiers, total langues: {total}")


if __name__ == "__main__":
    main()
