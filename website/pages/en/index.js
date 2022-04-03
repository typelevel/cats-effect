const React = require('react');

const CompLibrary = require('../../core/CompLibrary.js');

const MarkdownBlock = CompLibrary.MarkdownBlock; /* Used to read markdown */
const Container = CompLibrary.Container;
const GridBlock = CompLibrary.GridBlock;

class HomeSplash extends React.Component {
    render() {
        const {siteConfig, language = ''} = this.props;
        const {baseUrl, docsUrl} = siteConfig;
        const docsPart = `${docsUrl ? `${docsUrl}/` : ''}`;
        const langPart = `${language ? `${language}/` : ''}`;
        const docUrl = doc => `${baseUrl}${docsPart}${langPart}${doc}`;

        const SplashContainer = props => (
            <div className="homeContainer">
                <div className="homeSplashFade">
                    <div className="wrapper homeWrapper">{props.children}</div>
                </div>
            </div>
        );

        const Logo = props => (
            <div className="projectLogo">
                <img src={props.img_src} alt="Project Logo" />
            </div>
        );

        const ProjectTitle = props => (
            <h2 className="projectTitle">
                {props.title}
                <small>{props.tagline}</small>
            </h2>
        );

        const PromoSection = props => (
            <div className="section promoSection">
                <div className="promoRow">
                    <div className="pluginRowBlock">{props.children}</div>
                </div>
            </div>
        );

        const Button = props => (
            <div className="pluginWrapper buttonWrapper">
                <a className="button" href={props.href} target={props.target}>
                    {props.children}
                </a>
            </div>
        );

        return (
            <SplashContainer>
                <Logo img_src={`${baseUrl}img/cats-effect-logo.svg`} />
                <div className="inner">
                    <ProjectTitle tagline={siteConfig.tagline} title={siteConfig.title} />
                    <code className="hljs css language-sbt">
                        <span className="hljs-string">"org.typelevel"</span> %% <span className="hljs-string">"cats-effect"</span> % <span className="hljs-string">"3.3.10"</span>
                    </code>
                    <PromoSection>
                        <Button target="_blank" href="https://scastie.scala-lang.org/NGMkhrrER9W0rM7it8JyEA">Try It!</Button>
                        <Button href={docUrl('getting-started')}>Get Started</Button>
                    </PromoSection>
                </div>
            </SplashContainer>
        );
    }
}

class Index extends React.Component {
    render() {
        const {config: siteConfig, language = ''} = this.props;
        const {baseUrl} = siteConfig;

        const Block = props => (
            <Container
                padding={['bottom', 'top']}
                id={props.id}
                background={props.background}>
                <GridBlock
                    align={props.align}
                    contents={props.children}
                    layout={props.layout}
                />
            </Container>
        );

/*
def sleepPrint(word: String, name: String, rand: Random[IO]) =
  for {
    delay <- rand.betweenInt(200, 700)
    _     <- IO.sleep(delay.millis)
    _     <- IO.println(s"$word, $name")
  } yield ()

for {
  rand <- Random.scalaUtilRandom[IO]
  _    <- IO.println("What is your name?")
  name <- IO.readln

  english <- sleepPrint("Hello", name, rand).foreverM.start
  french  <- sleepPrint("Bonjour", name, rand).foreverM.start
  spanish <- sleepPrint("Hola", name, rand).foreverM.start

  _ <- IO.sleep(5.seconds)
  _ <- english.cancel >> french.cancel >> spanish.cancel
} yield ()
 */
        const Hook = () => (
            <Block background="light" align="left">
                {[
                    {
                        content:
                            "Cats Effect is a high-performance, asynchronous, composable framework for building real-world applications in a purely functional style within the [Typelevel](https://typelevel.org) ecosystem. It provides a concrete tool, known as \"the `IO` monad\", for capturing and controlling *actions*, often referred to as \"effects\", that your program wishes to perform within a resource-safe, typed context with seamless support for concurrency and coordination. These effects may be asynchronous (callback-driven) or synchronous (directly returning values); they may return within microseconds or run infinitely.\n\nEven more importantly, Cats Effect defines a set of typeclasses which define what it means to *be* a purely functional runtime system. These abstractions power a thriving ecosystem consisting of [streaming](https://fs2.io) [frameworks](https://monix.io), JDBC [database layers](https://tpolecat.github.io/doobie/), HTTP [servers](https://http4s.org) and [clients](https://sttp.softwaremill.com/en/latest/), asynchronous clients for systems like [Redis](https://redis4cats.profunktor.dev) and MongoDB, and so much more! Additionally, you can leverage these abstractions within your own application to unlock powerful capabilities with little-or-no code changes, for example solving problems such as dependency injection, multiple error channels, shared state across modules, tracing, and more.",
                        image: `${baseUrl}img/hello-printing.png`,
                        imageAlign: 'right',
                    }
                ]}
            </Block>
        );

        const TryOut = () => (
            <Block background="light" align="left">
                {[
                    {
                        content:
                            "Cats Effect uniquely defines what it means to be a functional effect. This gives it unmatched expressive and compositional power, unlocking an entirely ecosystem of extensibility and enriching your applications with the ability to re-mix the runtime system *itself*. This brings the full power of the Cats ecosystem to bear, enabling elegant answers to thorny problems like distributed tracing, dependency injection, error reporting along multiple channels, and much more!\n\n Cats Effect's system of abstractions are the culmination of years of research and experience in how to best apply functional programming concepts in real-world applications on the JVM and JavaScript, and particularly how to express such ideas within Scala.",
                        image: `${baseUrl}img/cats-effect-3.0-hierarchy.svg`,
                        imageAlign: 'left',
                        title: 'Powerful Abstract Calculus',
                    },
                ]}
            </Block>
        );

        const Description = () => (
            <Block background="dark" align="center">
                {[
                    {
                        content:
                            'This is another description of how this project is useful',
                        image: `${baseUrl}img/undraw_note_list.svg`,
                        imageAlign: 'right',
                        title: 'Description',
                    },
                ]}
            </Block>
        );

        const LearnHow = () => (
            <Block background="light" align="center">
                {[
                    {
                        content:
                            'Each new Docusaurus project has **randomly-generated** theme colors.',
                        image: `${baseUrl}img/undraw_youtube_tutorial.svg`,
                        imageAlign: 'right',
                        title: 'Randomly Generated Theme Colors',
                    },
                ]}
            </Block>
        );

        const Features = () => (
            <Block layout="fourColumn" align="center">
                {[
                    {
                        content: 'This is the content of my feature',
                        image: `${baseUrl}img/cats-effect-1.0-hierarchy.svg`,
                        imageAlign: 'top',
                        title: 'Feature One',
                    },
                    {
                        content: 'The content of my second feature',
                        image: `${baseUrl}img/undraw_operating_system.svg`,
                        imageAlign: 'top',
                        title: 'Feature Two',
                    },
                ]}
            </Block>
        );

        const Feature = feature => (
            <Block align="left">
                {[{
                    content: feature.children,
                    image: feature.image,
                    imageAlign: feature.align,
                    title: feature.title
                }]}
            </Block>
        );

        const Showcase = () => {
            if ((siteConfig.users || []).length === 0) {
                return null;
            }

            const showcase = siteConfig.users
                .filter(user => user.pinned)
                .map(user => (
                    <a href={user.infoLink} key={user.infoLink}>
                        <img src={user.image} alt={user.caption} title={user.caption} />
                    </a>
                ));

            const pageUrl = page => baseUrl + (language ? `${language}/` : '') + page;

            return (
                <div className="productShowcaseSection paddingBottom">
                    <h2>Adopters</h2>
                    <div className="logos">{showcase}</div>
                    <div className="disclaimer">(nothing in this section should be construed as a partnership or endorsement, implied or otherwise)</div>
                    <div className="more-users">
                        <a className="button" href={pageUrl('users')}>
                            More {siteConfig.title} Users
                        </a>
                    </div>
                </div>
            );
        };

/*
def read(socket: AsynchronousSocketChannel): IO[ByteBuffer] =
  IO async { callback =>
    IO {
      val buf = ByteBuffer.allocate(1024)

      // non-blocking read
      val f = socket.read(buf, (), new CompletionHandler {

        // invoke callback with result
        def completed(len: Integer, u: Unit) = {
          buf.flip()
          buf.limit(len.toInt)
          callback(Right(buf))
        }

        // invoke callback with error
        def failed(t: Throwable, u: Unit) =
          callback(Left(t))
      })

      Some(IO(f.cancel(true)))
    }
  }


def fetchAllS3(
    client: S3Client,
    bucket: String,
    names: List[String])
    : IO[List[String]] =
  names parTraverse { name =>
    client.getObjectAsString(bucket, name)
  }


java.lang.Throwable: A runtime exception has occurred
    at org.simpleapp.examples.Main$.b(Main.scala:28)
    at org.simpleapp.examples.Main$.a(Main.scala:25)
    at org.simpleapp.examples.Main$.$anonfun$foo$11(Main.scala:37)
    at map @ org.simpleapp.examples.Main$.$anonfun$foo$10(Main.scala:37)
    at flatMap @ org.simpleapp.examples.Main$.$anonfun$foo$8(Main.scala:36)
    at flatMap @ org.simpleapp.examples.Main$.$anonfun$foo$6(Main.scala:35)
    at flatMap @ org.simpleapp.examples.Main$.$anonfun$foo$4(Main.scala:34)
    at flatMap @ org.simpleapp.examples.Main$.$anonfun$foo$2(Main.scala:33)
    at flatMap @ org.simpleapp.examples.Main$.foo(Main.scala:32)
    at flatMap @ org.simpleapp.examples.Main$.program(Main.scala:42)
    at as @ org.simpleapp.examples.Main$.run(Main.scala:48)
    at main$ @ org.simpleapp.examples.Main$.main(Main.scala:22)


def endpoint[F[_]: Spawn](
    server: Server[F],
    body: Array[Byte] => F[Array[Byte]])
    : F[Unit] = {

  def handle(conn: Connection[F]): F[Unit] =
    for {
      request <- conn.read
      response <- body(request)
      _ <- conn.write(response)
    } yield ()

  MonadCancel[F] uncancelable { poll =>
    poll(server.accept) flatMap { conn =>
      poll(handle(conn)).guarantee(conn.close).start
    }
  }

  handler.foreverM
}


val first = IO(computeFirst())
val second = IO(computeSecond())

for {
  a <- first
  b <- second
} yield ()
 */
        return (
            <div>
                <HomeSplash siteConfig={siteConfig} language={language} />
                <div className="mainContainer">
                    <Hook />

                    <Feature align="left" title="Asynchronous" image="img/async.png">
                        The `IO` monad allows you to capture and control asynchronous, callback-driven effects behind a clean, synchronous interface. Although superficially similar to `Future`, `IO` takes this concept to the next level with a powerful API that leaves you fully in control of evaluation semantics and behavior. Write programs that seamlessly mix synchronous and asynchronous code without sacrificing code comprehension or composability.
                    </Feature>
                    <Feature align="right" title="Concurrency" image="img/concurrency.png">
                        `IO` can power highly concurrent applications, like web services that must serve tens of thousands of requests per second. Concurrency in `IO` is facilitated by fibers, which are lightweight, interruptible threads that are managed completely by the runtime. Fibers are much cheaper than native OS threads, so your application can spawn tens of millions without breaking a sweat. Focus on high-level concurrency control without worrying about details like thread management or executor shifting.
                    </Feature>
                    <Feature align="left" title="Tracing" image="img/tracing.png">
                        `IO` collects runtime information as your program executes, making it super-easy to track down the origin of errors or introspect your program as it evaluates. Tracing can be enabled in production without any noticable impact on performance, which automatically unlocks powerful features like enhanced exceptions that make it easier to diagnose errors. Full instrumentation is also supported for developer environments when tracking down thorny issues, even through monad transformers or third-party libraries.
                    </Feature>
                    <Feature align="right" title="Safety" image="img/safety.png">
                        Real-world applications must often deal with resources like network connections and file handles to serve requests. Resource management is an exceptionally difficult problem in concurrent applications; one slight bug could result in a memory leak that OOM-kills your service or even a deadlock that renders your service completely unresponsive. `IO` manages resource lifecycles for you and guarantees that resources are safely allocated and released even in the presence of exceptions and cancellations.
                    </Feature>
                    <Feature align="left" title="Composable" image="img/composable.png">
                        Cats Effect embraces purely functional programming: `IO` represents a description of a program rather than a running computation, which gives you ultimate control over how and when effects are evaluated. Simple programs can be composed to form more complex programs, while retaining the ability to reason about the behavior and complexity. Refactor without fear!
                    </Feature>

                    <TryOut />
                    <Showcase />
                </div>
            </div>
        );
    }
}

module.exports = Index;
