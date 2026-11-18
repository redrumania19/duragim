# Sahte Konum (Android)

Telefonun konumunu haritada seçtiğin bir noktaya sabitleyen Android uygulaması.
Durağım'ı ve diğer konum kullanan uygulamaları evden test etmek için.

## Özellikler
- Haritaya (OpenStreetMap) dokunarak ya da `41.0082, 28.9784` gibi koordinat yazarak konum seçme
- Adres veya yer adıyla arama ("Kadıköy iskele", "Taksim Meydanı"): önce Türkiye'de, bulamazsa tüm dünyada (OpenStreetMap Nominatim)
- Kayıtlı konumlar: "Ev", "İş" gibi isimle kaydet, üstteki çipten tek dokunuşla seç, basılı tutarak sil
- Saniyede bir GPS, ağ ve Google "fused" sağlayıcılarına sahte konum basma
- Çalışırken yeni noktaya dokununca konum anında oraya taşınır
- İsteğe bağlı ~2 m titreme (sabit nokta yerine gerçek GPS gibi oynar)
- Bildirimden tek dokunuşla durdurma
- Başka uygulamalardan `geo:` bağlantısıyla açılabilme

## Kurulum
1. APK'yı telefona yükle (bilinmeyen kaynaklara izin ver).
2. **Geliştirici seçeneklerini aç:** Ayarlar → Telefon hakkında → *Yapı numarası*'na 7 kez dokun.
3. Ayarlar → Sistem → Geliştirici seçenekleri → **Sahte konum uygulamasını seç** → *Sahte Konum*.
4. Uygulamayı aç, noktayı seç, **Konumu sabitle**.

## Derleme
```bash
./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```
Android SDK 35 ve JDK 17+ gerekir (`local.properties` içinde `sdk.dir`).

## Bilinen sınırlar
- Bazı uygulamalar (bankacılık, bazı oyunlar) sahte konumu `Location.isMock()` ile algılar.
- Konum yalnızca uygulama çalışırken (bildirim görünürken) sahtedir; durdurunca gerçek GPS'e döner.
