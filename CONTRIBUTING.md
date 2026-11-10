# Contributing

Thanks for helping. The most useful things right now, in order:

1. **A [TV report](https://github.com/Brendan-Black/SamsungTvClient/issues/new?template=tv-report.yml).** Only one model has
   been tried. Saying what worked on yours fills in the gaps no test can.
2. **A connector for an older generation** (`orsay`, `encrypted`). The README's *Adding a generation* section starts you off.
3. **A bug with a failing case.** See below.

## Building

JDK 25, or let Gradle download it.

```sh
./gradlew check     # formatting, compiler warnings, nulls, and every test; needs no TV and no network
./gradlew format    # formats every Java file
./gradlew smoke     # SSDP over real multicast; not part of check
```

`./gradlew javaStandardsIde` sets VS Code up to format and warn exactly as the build does.

## The standard

The code follows [Black Java](https://github.com/Brendan-Black/black-java/blob/master/BLACK_JAVA.md). The parts that come up most:

- **Make the type say it.** A value with a shape is a `TypedString` record that refuses anything else when constructed. Convert
  raw strings where they enter, then pass the type. Anything that goes into an SDB command must be typed or checked: SDB has no
  way to pass arguments separately.
- **Nulls don't cross a boundary.** Return `Optional`. A nullable record field says `@Nullable` and has an `Optional` accessor.
  `Optional` is never a parameter; write an overload. NullAway fails the build otherwise.
- **Don't swallow exceptions.** Catch only to retry, fall back on purpose (with a comment saying so), or translate, and keep the
  cause. Catch the narrowest type that fits.
- **Secrets stay out of `toString()`.** Tokens and keys get a redacted one.
- **Writes are atomic.** Use `AtomicFiles`.
- **Keep the JDK as the only runtime dependency.**

## Tests

There's no framework: `AllTests` runs groups of checks from `Check`. Tests are hermetic, so anything that talks to the outside
world goes through a seam with a strict pseudo behind it.

**Every bug becomes one more test case.** Reproducing a report should be as quick as adding that exact input to a list:
`TypesTest` for a value that's wrongly accepted or refused, a `PseudoHttp` canned reply for something a TV really sent. Add the
case, watch it fail, fix the code, and leave the case in.

Test the failure path too: what the TV sends when it's confused matters as much as when it's happy.

## Pull requests

`./gradlew check` should pass; CI runs it on Linux, Windows and macOS. Say in the description which TV you tried it on, if any.
