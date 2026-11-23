# macos/ci — 云端构建用的加密书库包

`bookstore.enc` 是《中庸》《论语》正文与《中庸》注释共 3 个文件的 tar.gz，
用 openssl 对称加密后的密文：

- `zhongyong/content/zhongyong.json`
- `zhongyong/notes/zhongyong_notes.json`
- `lunyu/content/lunyu.json`

这 3 个文件不在本 repo 内（源在书库工作副本 `bookstore/repos/`），且按内容
红线不得明文进这个公开 repo，所以云端构建（GitHub Actions，见
`.github/workflows/mac-build.yml`）从这里取密文：runner 上用口令解密到临时
目录，经 `BOOKSTORE_DIR` 喂给 `macos/build.sh`，构建结束 runner 销毁，明文
不落任何持久存储。

## 加密格式

```
openssl enc -aes-256-cbc -salt -md sha256
```

用 EVP_BytesToKey＋sha256 的经典格式，是为了兼容 macOS 自带的 LibreSSL
（其 `enc` 不支持 `-pbkdf2`）。口令是强随机串，本身的熵足够弥补 KDF 的弱。

## 口令在哪

- GitHub 侧：Actions secret `BOOKSTORE_KEY`（`gh secret set BOOKSTORE_KEY
  --repo gty3310/shixi` 存入），只有 workflow 运行时能读。
- 本机侧：`~/.config/muse/bookstore-key`（权限 600，不进 repo）。
- 绝不写进口令到 repo 文件、提交信息或日志。

## 内容更新时怎么换包

口令不变，本地重打包替换即可：

```sh
cd ~/workspace/songdu-player
export BOOKSTORE_KEY="$(cat ~/.config/muse/bookstore-key)"
tar -czf - -C ~/workspace/bookstore/repos \
  zhongyong/content/zhongyong.json \
  zhongyong/notes/zhongyong_notes.json \
  lunyu/content/lunyu.json \
| openssl enc -aes-256-cbc -salt -md sha256 -pass env:BOOKSTORE_KEY \
    -out macos/ci/bookstore.enc
```

然后提交 `macos/ci/bookstore.enc` 推送即可，下一次构建自动用新包。
