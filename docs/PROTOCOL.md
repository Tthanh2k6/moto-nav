# Giao thức điện thoại ⇄ màn hình (bản 1)

Tài liệu này là hợp đồng giữa app Android và firmware ESP32. Hai bên chỉ cần tuân theo đúng những gì ghi ở đây
là nói chuyện được với nhau; không bên nào cần biết bên kia viết bằng gì.

Nguyên tắc chia việc:

- **Điện thoại** làm mọi việc nặng: GPS, tính đường, bám tuyến, chọn đường nào đáng vẽ.
- **Màn hình** chỉ giữ một ít hình học quanh xe, tự xoay/phóng bản đồ và tự chạy hoạt ảnh giữa hai lần cập nhật.
  Nhờ vậy điện thoại chỉ cần gửi trạng thái khoảng 1 lần/giây mà hình vẫn mượt.

## 1. Kết nối Bluetooth LE

Màn hình là thiết bị ngoại vi (GATT server), điện thoại là trung tâm (GATT client).

| | UUID | Thuộc tính |
|---|---|---|
| Dịch vụ | `4d4e0001-6d6f-746f-6e61-760000000001` | có trong gói quảng bá |
| RX (điện thoại → màn) | `4d4e0002-6d6f-746f-6e61-760000000001` | write without response |
| TX (màn → điện thoại) | `4d4e0003-6d6f-746f-6e61-760000000001` | notify |

- Tên quảng bá: `MotoNav`. Không ghép đôi, không mã hoá (dữ liệu không nhạy cảm).
- Firmware đặt MTU 247. Điện thoại ghi mỗi gói tối đa `min(MTU − 3, 244)` byte, và 20 byte khi chưa thương lượng xong MTU.
- Điện thoại chỉ ghi gói kế tiếp sau khi gói trước được xác nhận đã ghi (mỗi lúc một gói đang bay).
- Hai đặc tính được coi là **một dòng byte liên tục** (giống cổng nối tiếp): một khung có thể nằm vắt qua nhiều gói,
  một gói có thể chứa phần của nhiều khung. Các khung không bao giờ bị trộn vào nhau: đã bắt đầu ghi khung nào
  thì ghi hết khung đó.

## 2. Khung

```
0xA5 | type:u8 | len:u16 | payload[len] | crc:u16
```

- Mọi số nhiều byte đều **little-endian**.
- `len` tối đa **480**. Khung dài nhất là 486 byte.
- `crc` là CRC-16/CCITT-FALSE (đa thức 0x1021, khởi tạo 0xFFFF, không đảo bit) tính trên `type`, `len`, `payload`.
- Bên nhận kiểm CRC cho **mọi** khung, kể cả khung có `type` lạ. CRC sai hoặc `len` > 480: bỏ byte 0xA5 đó,
  tìm 0xA5 kế tiếp ngay trong phần đã nhận.
- CRC đúng nhưng `type` lạ: bỏ qua cả khung, không coi là lỗi. Đây là cách giao thức mở rộng về sau.
- CRC đúng nhưng nội dung không hợp lệ: bỏ khung, đếm là lỗi định dạng. Không hợp lệ gồm: thiếu byte, **thừa byte**
  so với bảng của loại khung đó, số đếm vượt giới hạn, TEXT có `text_id` = 0 hoặc chuỗi không phải UTF-8 hợp lệ.
  (Vì vậy muốn thêm trường về sau thì phải dùng một `type` mới, không nối thêm vào khung cũ.)
- Bên nhận xoá bộ đệm giải mã mỗi lần kết nối và ngắt kết nối.

Giá trị để tự kiểm: CRC của chuỗi ASCII `123456789` là `0x29B1`; khung CLEAR hoàn chỉnh là `A5 30 00 00 39 09`.

## 3. Toạ độ và đơn vị

- **Mốc (anchor):** một điểm lat/lon do điện thoại chọn. Mọi hình học được gửi dưới dạng độ lệch so với mốc:
  `x` về phía đông, `y` về phía bắc, kiểu `i16`, đơn vị **0,25 m** (tầm ±8 191 m).
- Điện thoại đổi lat/lon sang mét bằng phép chiếu phẳng quanh mốc:
  `x = (lon − lon0) · 111320 · cos(lat0)`, `y = (lat − lat0) · 110540`, làm tròn tới đơn vị gần nhất.
  Giá trị vượt tầm thì **kẹp** lại ở ±32767, không bao giờ quay vòng.
- Khi xe cách mốc quá **4 000 m** (đường chim bay), điện thoại chọn mốc mới tại vị trí xe (`anchor_id` tăng 1)
  rồi gửi lại hình học theo mốc mới. Điện thoại không bao giờ gửi toạ độ có trị tuyệt đối lớn hơn 32000.
- **Quãng đường dọc tuyến `s`:** `u32`, đơn vị **0,1 m**, tính từ điểm đầu của tuyến hiện tại.
- Góc: `u16`, đơn vị 0,01°, 0 = bắc, tăng theo chiều kim đồng hồ.
- Các số định danh (`anchor_id`, `route_id`, `window_id`, `text_id`) là `u8` quay vòng; hai bên chỉ so sánh bằng nhau.
  Điện thoại khởi tạo chúng bằng số ngẫu nhiên mỗi lần app khởi động. `text_id` không bao giờ bằng 0.

## 4. Thông điệp điện thoại → màn hình

### 0x01 HELLO

Gửi đầu tiên sau mỗi lần kết nối. HELLO **bắt đầu một phiên mới**: màn hình bỏ hết tuyến, đường, điểm, chữ đang giữ
(kể cả phần đang nhận dở), vì các số định danh của phiên trước không còn nghĩa.

| Byte | Kiểu | Ý nghĩa |
|---|---|---|
| 0 | u8 | bản giao thức = 1 |
| 1 | u8 | giao diện: 0 tự động theo giờ (ngày từ 06:00 đến 18:00), 1 ngày, 2 đêm |
| 2 | u32 | giờ Unix (giây) |
| 6 | i16 | lệch múi giờ so với UTC (phút) |

### 0x10 NAV_STATE

Gửi khoảng 1 lần/giây **bất cứ khi nào đang kết nối**, kể cả lúc không có chuyến đi (khi đó `status` = 0).

| Byte | Kiểu | Trường | Ý nghĩa |
|---|---|---|---|
| 0 | u8 | status | 0 rảnh, 1 đang dẫn, 2 lệch tuyến, 3 đang tính lại, 4 đã tới, 5 mất GPS |
| 1 | u8 | flags | bit0 = dữ liệu mẫu (demo); bit1–2 = đổi giao diện: 0 giữ theo HELLO, 1 ngày, 2 đêm |
| 2 | u8 | anchor_id | mốc mà x, y dùng |
| 3 | u8 | route_id | tuyến mà các trường `*_s` dùng; tăng 1 mỗi lần đổi tuyến |
| 4 | i16 | x | vị trí xe |
| 6 | i16 | y | |
| 8 | u16 | heading | hướng đi của xe |
| 10 | u16 | speed | 0,1 km/h |
| 12 | u8 | limit | giới hạn tốc độ km/h, 0 = không biết |
| 13 | u8 | man_type | kiểu chỗ rẽ sắp tới, xem bảng dưới |
| 14 | u8 | man_exit | lối ra vòng xuyến (1..n), 0 nếu không phải vòng xuyến |
| 15 | u8 | text_id | TEXT nào là tên đường sắp rẽ vào; 0 = không có tên |
| 16 | u32 | route_s | xe đã đi tới đâu trên tuyến |
| 20 | u32 | man_s | vị trí chỗ rẽ sắp tới |
| 24 | u32 | prev_man_s | vị trí chỗ rẽ vừa qua (0 nếu chưa qua chỗ nào) |
| 28 | u32 | remaining | mét còn lại tới đích |
| 32 | u16 | eta_min | giờ đến, tính bằng phút kể từ 0 giờ địa phương; 0xFFFF = không biết |
| 34 | u8 | route_win | `window_id` của cửa sổ ROUTE mới nhất điện thoại đã phát |
| 35 | u8 | roads_win | `window_id` của cửa sổ ROADS mới nhất điện thoại đã phát |
| 36 | u8 | then_type | kiểu của chỗ rẽ **kế sau** chỗ sắp tới nếu hai chỗ cách nhau không quá 100 m; 0xFF = không có |
| 37 | u16 | then_gap | khoảng cách giữa hai chỗ rẽ đó, 0,1 m |
| 39 | u8 | n_alerts | số cảnh báo theo sau, tối đa 4 |
| … | 3 byte × n | alerts | `kind:u8` (0 đèn giao thông, 1 camera), `dist:u16` mét; gần nhất đứng trước |
| … | u8 | n_lanes | số làn theo sau, tối đa 8 |
| … | 1 byte × n | lanes | bit0–3 hướng của làn (mã như `man_type`), bit7 = làn nên đi |

Khoảng cách tới chỗ rẽ = `man_s − route_s`; màn hình tự tính để đếm lùi mượt.

Mã `man_type`:

| Mã | Kiểu | Mã | Kiểu |
|---|---|---|---|
| 0 | đi thẳng | 6 | ngoặt gắt phải |
| 1 | chếch trái | 7 | quay đầu |
| 2 | rẽ trái | 8 | vòng xuyến |
| 3 | ngoặt gắt trái | 9 | tới đích |
| 4 | chếch phải | 10 | giữ trái ở chỗ đường tách đôi |
| 5 | rẽ phải | 11 | giữ phải ở chỗ đường tách đôi |

Mã lạ thì màn hình vẽ như "đi thẳng".

### 0x11 TEXT

| Byte | Kiểu | Ý nghĩa |
|---|---|---|
| 0 | u8 | text_id (1..255) |
| 1 | … | chuỗi UTF-8, tối đa 120 byte, không có byte 0 kết thúc |

Màn hình chỉ cần nhớ TEXT mới nhất. Điện thoại chuẩn hoá chuỗi trước khi gửi để màn hình (dùng phông bitmap) vẽ được:

1. Chuẩn hoá Unicode dạng NFC (chữ có dấu là một ký tự dựng sẵn).
2. Đổi dấu cách không ngắt thành dấu cách; `‘ ’` thành `'`; `“ ”` thành `"`; `– —` thành `-`; gộp các dấu cách liền nhau.
3. Ký tự nằm ngoài bộ chữ của màn hình thì đổi thành `?`. Bộ chữ gồm: ASCII 0x20–0x7E, 134 chữ cái tiếng Việt
   dựng sẵn (U+00C0–00C3, C8–CA, CC, CD, D2–D5, D9, DA, DD và dạng thường; U+0102/0103, 0110/0111, 0128/0129,
   0168/0169, 01A0/01A1, 01AF/01B0; U+1EA0–1EF9) và dấu ba chấm U+2026.
4. Rút gọn đầu tên: `Quốc lộ ` → `QL `, `Tỉnh lộ ` → `TL `, `Đại lộ ` → `ĐL `, `Hương lộ ` → `HL `;
   bỏ chữ `Đường ` ở đầu trừ khi từ kế tiếp bắt đầu bằng chữ số hoặc là `số`. Không đụng tới `Hẻm`.
5. Cắt còn tối đa 120 byte, không cắt giữa một ký tự.

### 0x20 ANCHOR

| Byte | Kiểu | Ý nghĩa |
|---|---|---|
| 0 | u8 | anchor_id |
| 1 | i32 | vĩ độ × 10⁷ |
| 5 | i32 | kinh độ × 10⁷ |

ANCHOR chỉ **thông báo** một mốc; nó không xoá gì. Mọi ROUTE/ROADS/POIS đều tự mang `anchor_id` của mình.

### 0x21 ROUTE

Một đoạn của tuyến quanh xe (không phải cả tuyến), gọi là một **cửa sổ**. Cửa sổ dài thì chia nhiều phần.

| Byte | Kiểu | Trường | Ý nghĩa |
|---|---|---|---|
| 0 | u8 | window_id | tăng 1 mỗi lần phát một cửa sổ ROUTE mới |
| 1 | u8 | route_id | |
| 2 | u8 | anchor_id | |
| 3 | u8 | part | 0..parts−1 |
| 4 | u8 | parts | |
| 5 | u8 | flags | bit0: điểm cuối của phần cuối là đích |
| 6 | u32 | start_s | `s` của điểm đầu tiên trong phần này |
| 10 | u32 | end_s | `s` của điểm cuối cùng trong phần này |
| 14 | u16 | n | số điểm (2..116) |
| 16 | 4 byte × n | points | `x:i16`, `y:i16` |

- Phần sau bắt đầu bằng chính điểm cuối của phần trước.
- Màn hình cộng dồn độ dài các đoạn rồi **co giãn** để phần đó trải đúng từ `start_s` tới `end_s`
  (làm tròn toạ độ khiến tổng độ dài lệch đôi chút so với số của điện thoại).
- Một cửa sổ có tối đa **512 điểm** (điểm nối giữa hai phần chỉ tính một lần).
- Khi tuyến nằm ngoài tầm toạ độ, điện thoại có thể gửi một cửa sổ chỉ gồm 2 điểm với `end_s` gần bằng (hoặc bằng)
  `start_s`; màn hình phải chịu được trường hợp này khi co giãn độ dài.

### 0x22 ROADS

Các đường xung quanh xe. Chia phần như ROUTE.

| Byte | Kiểu | Trường |
|---|---|---|
| 0 | u8 | window_id (tăng 1 mỗi lần phát một cửa sổ ROADS mới) |
| 1 | u8 | anchor_id |
| 2 | u8 | part |
| 3 | u8 | parts |
| 4 | … | các con đường nối tiếp nhau tới hết payload |

Mỗi con đường:

| Kiểu | Ý nghĩa |
|---|---|
| u8 | bit0–1 cấp đường (0 lớn nhất … 3 hẻm), bit2 = đường này chạm vào tuyến |
| u8 | số điểm n (2..110) |
| 4 byte × n | `x:i16`, `y:i16` |

- Đường dài hơn 110 điểm thì cắt thành nhiều con, điểm nối lặp lại. Một con đường không nằm vắt qua hai phần.
- Một cửa sổ có tối đa **1 024 con đường** và **4 096 điểm**. Cửa sổ rỗng (một phần, không có đường nào) là hợp lệ,
  và điện thoại luôn phát một cửa sổ ROADS cho mỗi mốc, dù rỗng.

### 0x23 POIS

Các điểm vẽ lên bản đồ (đèn giao thông, camera), đi kèm một cửa sổ ROADS và được phát **trước** các phần ROADS của cửa sổ đó.

| Byte | Kiểu | Ý nghĩa |
|---|---|---|
| 0 | u8 | window_id của cửa sổ ROADS mà nó đi kèm |
| 1 | u8 | anchor_id |
| 2 | u8 | n (tối đa 90) |
| 3 | 5 byte × n | `kind:u8`, `x:i16`, `y:i16` |

### 0x30 CLEAR

Không có payload. Màn hình xoá tuyến, đường, điểm, chữ và về trạng thái rảnh.

## 5. Thông điệp màn hình → điện thoại

### 0x02 INFO

Gửi sau khi nhận HELLO.

| Byte | Kiểu | Ý nghĩa |
|---|---|---|
| 0 | u8 | bản giao thức màn hình hiểu |
| 1 | u8 | bản firmware (chính) |
| 2 | u8 | bản firmware (phụ) |
| 3 | u16 | chiều rộng (pixel) |
| 5 | u16 | chiều cao (pixel) |
| 7 | u8 | khả năng: bit0 có cảm ứng, bit1 có cảm biến ánh sáng |
| 8 | u16 | (tuỳ chọn) số điểm ROUTE tối đa một cửa sổ |
| 10 | u16 | (tuỳ chọn) số điểm ROADS tối đa một cửa sổ |
| 12 | u16 | (tuỳ chọn) số con đường tối đa một cửa sổ |
| 14 | u8 | (tuỳ chọn) số POI tối đa |

INFO dài 8 byte hoặc 15 byte. Không có phần tuỳ chọn, hoặc không nhận được INFO, thì điện thoại dùng các giới hạn ở mục 4.

### 0x03 SYNC_REQUEST

| Byte | Kiểu | Ý nghĩa |
|---|---|---|
| 0 | u8 | lý do: 1 lỗi khung, 2 thiếu hình học, 3 thiếu chữ |
| 1 | u8 | cần gì: bit0 ROUTE, bit1 ROADS + POIS, bit2 TEXT |

- Điện thoại chỉ phát lại đúng những thứ được yêu cầu (kèm ANCHOR, với `window_id` mới), và bỏ qua yêu cầu
  nếu thứ đó vẫn còn nằm trong hàng đợi gửi.
- Màn hình gửi tối đa một yêu cầu mỗi 5 giây, không gửi khi thứ đang thiếu vừa có khung tới trong 3 giây gần đây,
  và không bao giờ gửi chỉ vì vừa kết nối.

## 6. Trình tự

1. Kết nối → điện thoại bật notify trên TX, thương lượng MTU, gửi HELLO.
2. Ngay sau HELLO điện thoại gửi một NAV_STATE (trạng thái hiện tại, hoặc `status` = 0), để màn hình rời màn
   "mất kết nối" ngay.
3. Màn hình bỏ trạng thái cũ, đáp INFO. (Không thấy INFO sau 1 giây thì điện thoại vẫn đi tiếp.)
4. Điện thoại gửi đồng bộ đầy đủ: TEXT, ANCHOR, ROUTE, NAV_STATE, POIS, ROADS.
5. Sau đó mỗi giây một NAV_STATE; hình học chỉ gửi lại khi cần (mục 7).
6. Kết thúc chuyến: CLEAR, rồi NAV_STATE với `status` = 0 mỗi giây.

## 7. Điện thoại gửi gì, lúc nào

| Thứ | Phạm vi | Phát lại khi |
|---|---|---|
| ROUTE | từ 400 m sau xe tới 3 000 m trước xe | phần phía trước còn dưới 1 200 m mà chưa tới đích; đổi tuyến; đổi mốc |
| ROADS | hình vuông cạnh 2 000 m quanh xe, đường bị cắt tại mép hình vuông; đường cấp 2–3 chỉ lấy đoạn cách tuyến dưới 250 m | xe cách tâm cửa sổ hơn 350 m; đổi tuyến; đổi mốc; có thêm dữ liệu đường |
| POIS | các điểm nằm trên tuyến, trong cùng hình vuông với ROADS | cùng lúc với ROADS |

Nếu vượt giới hạn của màn hình, điện thoại lược bớt theo thứ tự: làm thưa điểm (sai số 1 m); bỏ đường cấp 3 không chạm tuyến,
xa xe nhất trước; rồi tới cấp 2 không chạm tuyến; rồi cấp 0–1; cuối cùng mới tới các đường nhỏ có chạm tuyến;
với ROUTE thì cắt ngắn đầu xa.

**Thứ tự ưu tiên khi gửi.** Điện thoại xếp khung vào ba hàng và luôn ghi trọn từng khung:

| Hàng | Khung | Quy tắc |
|---|---|---|
| Quan trọng | HELLO, CLEAR, TEXT, ANCHOR, ROUTE | vào trước ra trước, không bao giờ bị bỏ |
| Trạng thái | NAV_STATE | chỉ giữ một cái: cái mới thay cái đang chờ |
| Khối lớn | POIS, ROADS | vào trước ra trước; phát cửa sổ mới thì bỏ các khung chưa gửi của cửa sổ cũ |

Mỗi lần chọn khung để ghi: lấy hàng "quan trọng" trước, rồi "trạng thái", rồi "khối lớn". Nhờ vậy NAV_STATE không phải
chờ sau 15 KB đường phụ, nhưng cũng không bao giờ vượt lên trước TEXT/ANCHOR/ROUTE mà nó nhắc tới.
HELLO và CLEAR xoá mọi khung đang chờ trong hàng "trạng thái" và "khối lớn".

## 8. Màn hình giữ và dùng dữ liệu thế nào

**Ghép cửa sổ.** Với mỗi loại (ROUTE, ROADS) màn hình có một ô *đang dùng* và một ô *đang nhận*.

- Phần 0 luôn mở một cửa sổ đang nhận mới và bỏ cái đang nhận dở trước đó.
- Phần k > 0 chỉ được nhận nếu `window_id` và `parts` khớp với cửa sổ đang nhận và k đúng là phần kế tiếp;
  không khớp thì bỏ cả cửa sổ đang nhận lẫn khung đó. Khung loại khác được phép xen giữa các phần.
- Cửa sổ vượt giới hạn thì bỏ.
- POIS được giữ kèm cửa sổ ROADS có cùng `window_id` và lên "đang dùng" cùng lúc với nó.
- Cửa sổ nhận đủ thì lên "đang dùng" ngay nếu `anchor_id` của nó trùng mốc đang dùng (hoặc chưa có gì đang dùng).
  Nếu khác mốc, nó được giữ lại cho tới khi **cả ROUTE lẫn ROADS** của mốc mới đều đủ, rồi cả hai lên cùng lúc
  và mốc đang dùng đổi theo. Nhờ vậy bản đồ không bao giờ trống hay vẽ lẫn hai hệ toạ độ lúc đổi mốc.

**Vị trí xe.**

- Đang trên tuyến (`status` = 1 và `route_id` khớp cửa sổ ROUTE đang dùng): xe nằm tại điểm ứng với `route_s`
  trên tuyến mà màn hình đang giữ; `x`, `y` bị bỏ qua. Giữa hai NAV_STATE, màn hình cho `route_s` tiến theo `speed`,
  tối đa 2 giây, rồi nhập dần về giá trị mới khi nhận được.
- Các trường hợp khác: xe nằm tại `x`, `y` (chỉ dùng khi `anchor_id` khớp mốc đang dùng), tiến theo `heading` với `speed`, tối đa 2 giây.
- NAV_STATE có `route_id` hay `anchor_id` không khớp vẫn được dùng cho `status`, `speed` và để biết còn liên lạc.

**Hướng bản đồ.** Đang trên tuyến thì lấy theo dây cung của tuyến từ 4 m sau xe tới 12 m trước xe, làm mượt (hằng số thời gian 0,35 s).
Lệch tuyến thì lấy `heading`, và giữ nguyên khi tốc độ dưới 5 km/h.

**Phóng to giao lộ.** Tầm nhìn phía trước 300 m lúc bình thường, 85 m lúc sát chỗ rẽ. Bắt đầu phóng khi còn 260 m,
phóng hết cỡ ở 110 m; qua chỗ rẽ 20 m thì thu nhỏ dần trong 50 m tiếp theo (hằng số thời gian 0,7 s).
Lần vẽ đầu tiên, hoặc sau hơn 0,5 giây không vẽ, thì nhảy thẳng tới giá trị đích thay vì làm mượt.

**Lược bớt đường.** Cấp 0–1 luôn vẽ; cấp 2 vẽ nếu có cờ "chạm tuyến"; còn lại chỉ hiện dần theo mức phóng to.

**Biết mình thiếu gì.** Chỉ xét khi `status` khác 0 (lúc rảnh không có hình học nào để thiếu).
Màn hình coi một thứ là *thiếu* và gửi SYNC_REQUEST (theo giới hạn ở mục 5) khi:

- `route_win` / `roads_win` trong NAV_STATE không phải cửa sổ đang dùng, cũng không phải cửa sổ đã nhận đủ đang chờ,
  và 3 giây rồi không có khung loại đó tới;
- `text_id` khác chữ đang giữ suốt 3 giây;
- `status` = 1 và `route_s` cách cuối cửa sổ ROUTE đang dùng dưới 300 m mà cửa sổ đó không có cờ "là đích".

**Mất liên lạc.** Quá 3 giây không có NAV_STATE: làm mờ biểu tượng xe và ngừng nội suy. Quá 10 giây (hoặc chưa từng nhận gì):
chuyển sang màn "mất kết nối với điện thoại", không để lại chỉ dẫn cũ trên màn.

## 9. Mở rộng thử nghiệm: chiếu màn hình điện thoại

Để thử sức chip: chạm màn hình thì màn hiện lại thứ đang chiếu trên điện thoại (ví dụ YouTube), chạm lần nữa thì về
bản đồ. Không có tiếng (tiếng vẫn ở điện thoại). **Chỉ dùng khi xe đứng yên**: điện thoại không phát khi xe đang chạy,
màn hình cũng tự về bản đồ nếu NAV_STATE báo tốc độ từ 10 km/h trở lên.

Các khung dưới đây là `type` mới. Bên nào chưa hiểu thì bỏ qua theo mục 2, nên không cần đổi bản giao thức.

### 0x04 VIDEO_REQ (màn hình → điện thoại)

| Byte | Kiểu | Ý nghĩa |
|---|---|---|
| 0 | u8 | 1 = xin chiếu, 0 = thôi chiếu |

Màn hình gửi mỗi lần người dùng chạm để bật/tắt. Điện thoại đáp bằng VIDEO_STATUS. Điện thoại tự coi là "thôi chiếu" khi
đứt kết nối; màn hình tự thôi chiếu khi nhận HELLO.

### 0x40 VIDEO_STATUS (điện thoại → màn hình)

| Byte | Kiểu | Ý nghĩa |
|---|---|---|
| 0 | u8 | 0 đã tắt, 1 đang chiếu, 2 chưa bật chia sẻ màn hình trong app, 3 khoá vì xe đang chạy |

Gửi để đáp VIDEO_REQ, và mỗi khi trạng thái đổi trong lúc màn hình đang xin chiếu. Hàng "quan trọng" (mục 7).

### 0x41 VIDEO (điện thoại → màn hình)

Một ảnh JPEG được chia thành nhiều phần.

| Byte | Kiểu | Ý nghĩa |
|---|---|---|
| 0 | u16 | `frame_id`, tăng 1 mỗi ảnh (quay vòng) |
| 2 | u8 | `part`, 0 … parts − 1 |
| 3 | u8 | `parts`, 1 … 64 |
| 4 | u16 | `total`: tổng số byte của ảnh JPEG, 1 … 24 576 |
| 6 | … | dữ liệu, 1 … 474 byte |

- Ảnh là JPEG **baseline** (không progressive), cỡ tối đa 320 × 240; điện thoại gửi 320 × 180 (khung 16:9).
  Màn hình đặt ảnh vào giữa màn.
- Không hợp lệ (bỏ khung, đếm lỗi định dạng): `parts` ngoài 1 … 64, `part` ≥ `parts`, `total` ngoài 1 … 24 576,
  không có byte dữ liệu.
- Ghép: phần 0 mở ảnh mới và bỏ ảnh đang ghép dở. Phần k > 0 chỉ nhận nếu `frame_id`, `parts`, `total` khớp ảnh đang ghép
  và k đúng là phần kế tiếp; không khớp thì bỏ ảnh đang ghép. Phần cuối nhận xong mà số byte khác `total`, hoặc số byte
  vượt `total` giữa chừng, thì bỏ ảnh. Ghép đủ thì vẽ.
- Hàng gửi: hàng thứ tư **"hình"**, thấp hơn cả "khối lớn". Chỉ giữ một ảnh: ảnh mới vào thì bỏ các phần chưa gửi của ảnh cũ.
  Điện thoại chỉ chụp ảnh kế tiếp khi hàng "hình" đã trống, nên số ảnh/giây tự khớp với tốc độ đường truyền.
