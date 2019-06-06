# Cryptographically Verifying Releases

All release artifacts are cryptographically signed by a GPG key from one of [the maintainers](https://github.com/typelevel/cats-effect/graphs/contributors). Every release corresponds to a tag of the form `/v(\d+)\.(\d+)(\.(\d+))?` which is pushed to [the upstream Git repository](https://github.com/typelevel/cats-effect), and that tag is always signed by the *same* key.

To locally cryptographically verify the integrity of a release, you should start by verifying the tag itself:

```bash
$ git verify-tag v0.2
```

(replace `v0.2` with the version you're checking)

The output should contain a line like this:

```
gpg: Signature made Sun 14 May 2017 10:04:42 AM PDT using RSA key ID 2BAE5960
```

Note the last eight characters, which are the signature suffix of the signing key. You can also look at this tag on Github and, if you trust their servers, verify that it is linked to a profile you trust. An even better way of doing this is to visit [Keybase](https://keybase.io) and search for that 8 character signature, since this can be done without trusting any third parties (or rather, without trusting any single third party).

Once you've verified that the key signature matches someone you would expect to be releasing cats-effect artifacts, you should import the key to pin it for subsequent verifications:

```bash
$ gpg --recv-keys 2BAE5960
```

(replace those eight characters with the signature from above)

Now when you run `verify-tag`, you should see something close to the following:

```
gpg: Signature made Sun 14 May 2017 10:04:42 AM PDT using RSA key ID 2BAE5960
gpg: Good signature from "Daniel Spiewak <djspiewak@gmail.com>"
gpg:                 aka "Daniel Spiewak <daniel.spiewak@verizon.com>"
gpg:                 aka "Daniel Spiewak <daniel.spiewak@one.verizon.com>"
gpg:                 aka "[jpeg image of size 63185]"
gpg: WARNING: This key is not certified with a trusted signature!
gpg:          There is no indication that the signature belongs to the owner.
Primary key fingerprint: 34B6 C7D3 67D4 5628 0A1B  0A53 3587 7FB3 2BAE 5960
```

It's always a good exercise to take that primary key fingerprint (all 120 characters) and ensure that it matches the other key sources (e.g. Keybase). It is relatively easy to generate keys which signature collide on the final eight bits.

Now that you've grabbed the signature of the tag and verified that it correspond to an individual you would *expect* should be pushing cats-effect releases, you can move on to verifying the artifacts themselves.

```bash
sbt check-pgp-signatures
```

You will need the [sbt-gpg](http://www.scala-sbt.org/sbt-pgp/index.html) plugin to run this command. It will grab all of the signatures for all of your dependencies and verify them. Each one should indicate either `[OK]` or `[UNTRUSTED(...)]`. Each `UNTRUSTED` artifact will list the signature of the signing key, just as with the tag verification. Since we have already imported the key of the developer who signed the release tag, we should *definitely* see `[OK]` for the cats-effect artifact:

```
[info]    org.typelevel :    cats-effect_2.12 :    0.2 : jar   [OK]
```

If you do see `UNTRUSTED` (which will happen if you don't import the key), it should look like the following:

```
[info]    org.typelevel :    cats-effect_2.12 :    0.2 : jar   [UNTRUSTED(0x2bae5960)]
```

The signature *must* match the signature of the key which signed the tag (returned from the `git verify-tag` command previously). If it doesn't, you shouldn't trust the artifact on your classpath and should *immediately* sound the alarm in the [typelevel/cats Gitter channel](https://gitter.im/typelevel/cats).

If you see anything other than `OK` or `UNTRUSTED`, then something is *horribly* wrong and you should similarly sound the alarm.
