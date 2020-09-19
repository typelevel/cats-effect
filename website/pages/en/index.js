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
                <Logo img_src={`${baseUrl}img/cats-effect-logo.png`} />
                <div className="inner">
                    <ProjectTitle tagline={siteConfig.tagline} title={siteConfig.title} />
                    <code className="hljs css language-sbt">
                        <span className="hljs-string">"org.typelevel"</span> %% <span className="hljs-string">"cats-effect"</span> % <span className="hljs-string">"2.2.0"</span>
                    </code>
                    <PromoSection>
                        <Button href="https://scastie.scala-lang.org/gNCY9l6URP65xABbisTqlQ">Try It!</Button>
                        <Button href={docUrl('installation.html')}>Get Started</Button>
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

        const FeatureCallout = () => (
            <div
                className="productShowcaseSection paddingBottom"
                style={{textAlign: 'center'}}>
                <h2>Things You Get For Free</h2>
                <ul align="left">
                    <li>Resource safety</li>
                    <li>Parallelism</li>
                    <li>Optimal thread pool utilization with granular control over throughput *and* fairness</li>
                    <li>Easy refactoring of complex code at all layers of the stack</li>
                    <li>Pervasive asynchrony enabling extremely high scale</li>
                </ul>
            </div>
        );

        const TryOut = () => (
            <Block background="light" align="left">
                {[
                    {
                        content:
                            'Cats Effect uniquely defines what it means to be a functional effect. This gives it unmatched expressive and compositional power, unlocking an entirely ecosystem of extensibility and enriching your applications with the ability to re-mix the runtime system *itself*. This brings the full power of the Cats ecosystem to bear, enabling elegant answers to thorny problems like distributed tracing, dependency injection, error reporting along multiple channels, and much more!',
                        image: `${baseUrl}img/cats-effect-1.0-hierarchy.svg`,
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
                    <div className="more-users">
                        <a className="button" href={pageUrl('users.html')}>
                            More {siteConfig.title} Users
                        </a>
                    </div>
                </div>
            );
        };

        return (
            <div>
                <HomeSplash siteConfig={siteConfig} language={language} />
                <div className="mainContainer">
                    <Hook />

                    <Feature align="left" title="Asynchronous" image="img/async.png">
                        The `IO` monad allows you to capture and control asynchronous, callback-driven effects behind a clean, synchronous interface. Write programs that seamlessly mix synchronous and asynchronous code without sacrificing code comprehension or composability.
                    </Feature>
                    <Feature align="right" title="Concurrency" image="img/fibers.svg">
                        `IO` can power highly concurrent applications, like web services that must serve tens of thousands of requests per second. Concurrency in `IO` is facilitated by fibers, which are lightweight, interruptible threads that are managed completely by the runtime. Fibers are much cheaper than native OS threads, so your application can spawn tens of millions without breaking a sweat. Focus on high-level concurrency control without worrying about details like thread management or executor shifting.
                    </Feature>
                    <Feature align="left" title="Tracing" image="img/tracing.png">
                        `IO` collects runtime information as your program executes, making it super-easy to track down the origin of errors or introspect your program as it evaluates. Tracing can be enabled in production without any noticable impact on performance, which automatically unlocks powerful features like enhanced exceptions that make it easier to diagnose errors. Full instrumentation is also supported for developer environments when tracking down thorny issues, even through monad transformers or third-party libraries.
                    </Feature>
                    <Feature align="right" title="Safety" image="img/safety.png">
                        Real-world applications must often deal with resources like network connections and file handles to serve requests. Resource management is an exceptionally difficult problem in concurrent applications; one slight bug could result in a memory leak that OOM-kills your service or even a deadlock that renders your service completely unresponsive. `IO` manages resource lifecycles for you and guarantees that resources are safely allocated and released even in the presence of exceptions and cancellations.
                    </Feature>
                    <Feature align="left" title="Composable" image="img/composable.png">
                        Cats Effect embraces purely functional programming: `IO` represents a description of a program rather than a running computation, which gives you ultimate control over how and when effects are evaluated. Simple programs can be composed to form more complex programs, while retaining the ability to reason about the behavior and complexity.
                    </Feature>

                    <FeatureCallout />
                    <TryOut />
                    <Showcase />
                </div>
            </div>
        );
    }
}

module.exports = Index;
