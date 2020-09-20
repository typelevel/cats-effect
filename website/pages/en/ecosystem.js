const React = require('react');

const CompLibrary = require('../../core/CompLibrary');

const Container = CompLibrary.Container;

// TODO: add descriptions, CE2/CE3 compatibility, maintenance status, etc.
// TODO: probably use objects
const sections = [
  {
    name: 'Configuration',
    projects: [
      ['Ciris', 'https://cir.is/'],
      ['PureConfig', 'https://pureconfig.github.io/']
    ]
  },
  {
    name: 'Databases',
    projects: [
      ['doobie', 'https://tpolecat.github.io/doobie/'],
      ['redis4cats', 'https://github.com/profunktor/redis4cats'],
      ['Scanamo', 'https://github.com/scanamo/scanamo']
    ]
  },
  {
    name: 'HTTP',
    projects: [
      ['finch', 'https://finagle.github.io/finch/'],
      ['http4s', 'https://http4s.org'],
      ['sttp', 'https://sttp.softwaremill.com/en/latest/']
    ]
  },
  {
    name: 'Logging',
    projects: [
      ['Console4cats', 'https://console4cats.profunktor.dev/'],
      ['Log4cats', 'https://christopherdavenport.github.io/log4cats/'],
      ['Odin', 'https://github.com/valskalla/odin']
    ]
  },
  {
    name: 'Streaming',
    projects: [
      ['fs2', 'https://fs2.io']
    ]
  },
  {
    name: 'Testing',
    projects: [
      ['cats-effect-testing', 'https://github.com/djspiewak/cats-effect-testing'],
      ['munit-cats-effect', 'https://github.com/typelevel/munit-cats-effect'],
      ['Weaver Test', 'https://disneystreaming.github.io/weaver-test/']
    ]
  }
];

function Ecosystem(props) {
  const renderSections = sections.map((section, i) => {
    const renderProjects = section.projects.map((project, j) => (
      <li key={j}>
        <a href={project[1]}>{project[0]}</a>
      </li>
    ));
    return (
      <section key={i}>
        <header className="postHeader">
          <h2>{section.name}</h2>
        </header>
        <ul>
          {renderProjects}
        </ul>
      </section>
    );
  });
  return (
    <div className="docMainWrapper wrapper">
      <Container className="mainContainer versionsContainer">
        <div className="post">
          <header className="postHeader">
            <h1>Ecosystem</h1>
          </header>

          <p>
            Cats Effect enjoys a thriving ecosystem of libraries and frameworks that support a wide variety of applications. If you want to include your project in this list, feel free to open an issue or PR!
          </p>

          {renderSections}

        </div>
      </Container>
    </div>
  );
}

module.exports = Ecosystem;
