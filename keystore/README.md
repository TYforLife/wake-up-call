`debug.keystore` is the standard Android **debug** key (store and key password `android`, alias
`androiddebugkey`). It is committed on purpose so every CI build is signed the same way and installs over
the previous one. It must never sign a release; the Play release key will live in GitHub secrets.
