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
                <Logo img_src={`${baseUrl}img/favicon.png`} />
                <div className="inner">
                    <ProjectTitle tagline={siteConfig.tagline} title={siteConfig.title} />
                    <code class="hljs css language-sbt"><span class="hljs-string">"org.typelevel"</span> %% <span class="hljs-string">"cats-effect"</span> % <span class="hljs-string">"2.2.0"</span></code>
                    <PromoSection>
                        <Button href="https://scastie.scala-lang.org/Kt4fZowKRiGIR1oRefMLbQ">Try It!</Button>
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
                            "Cats Effect is a high-performance, asynchronous, composable framework for building real-world applications in a purely functional style within the [Typelevel](https://typelevel.org) ecosystem. It provides a concrete tool, known as \"the `IO` monad\", for capturing and controlling *actions*, often referred to as \"effects\", that your program wishes to perform within a resource-safe, typed context with seamless support for concurrency and coordination. These effects may be asynchronous (callback-driven) or synchronous (directly returning values); they may return within microseconds or run infinitely.\n\nEven more importantly, Cats Effect provides a set of typeclasses which define what it means to *be* a purely functional runtime system. These abstractions power a thriving ecosystem consisting of streaming frameworks, JDBC database layers, HTTP servers and clients, asynchronous clients for systems like Redis and MongoDB, and so much more! Additionally, you can leverage these abstractions within your own application to unlock powerful capabilities with little-or-no code changes, for example solving problems such as dependency injection, multiple error channels, shared state across modules, tracing, and more.",
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
                        `IO` is a lot like a `Future` that is always fully under your control.
                    </Feature>
                    <Feature align="right" title="Fibers" image="img/fibers.svg">
                        Fibers are like lightweight, interruptible threads that run concurrently with other fibers. Unlike threads, fibers require almost no resources to manage and have no special impact on garbage collection, so you can easily spawn tens of millions without breaking a sweat. Fibers save you from having to worry about the details of thread management or juggling executors across asynchronous callbacks while providing a powerful building block for high-level concurrency control.
                    </Feature>
                    <Feature align="left" title="Tracing" image="img/tracing.png">
                        `IO` automatically detects backtrace information for every step of your program, making it super-easy to track down the origin of errors or introspect your program as it evaluates, regardless of how much concurrency or how many asynchronous calls are involved. Tracing has a low enough overhead that you can run it in production without a noticeable impact, meaning even unexpected errors found in your logs are now easily diagnosed. Full instrumentation is also supported for developer environments when tracking down thorny issues, even through monad transformers or third-party libraries.
                    </Feature>
                    <Feature align="right" title="Safety" image="img/safety.png">
                        Testing feature things!
                    </Feature>
                    <Feature align="left" title="Composable" image="img/composable.png">
                        Testing feature things!
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
