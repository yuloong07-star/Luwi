# 浮标外观 (art 样例)

"只做美术的人"那条路: 一份 `theme/ball.json` + 几张图 = 一套新的浮标外观, **不写一行代码、
也不用装第二个 APK**。

```powershell
cd samples\art-ball-theme
python tools\build-art.py
node ..\..\tools\lw-plugin-sign.mjs sign --dir plugin --key ..\..\..\backups\lw-publisher-key.pem `
  --out ..\..\.lwtmp\art-ball --zip ..\..\.lwtmp\art-ball\art-ball-theme-1.0.0.lwp
```

装完在 Luwi → 设置 → 插件 → **「浮标外观」** 那一行里挑它; 换回"内置画法"也是那一行。
