const React = require('react');

const CompLibrary = require('../../core/CompLibrary');

const Container = CompLibrary.Container;

const users = [
  ['AccionLabs', 'https://www.accionlabs.com/'],
  ['Agoda', 'https://www.agoda.com/'],
  ['Azavea', 'https://www.azavea.com/'],
  ['Anduin Transactions', 'https://www.anduintransact.com/'],
  ['Banno Group', 'https://banno.com'],
  ['BUX', 'https://getbux.com/'],
  ['Cleverbase', 'https://cleverbase.com/'],
  ['Comcast', 'https://comcast.com'],
  ['Compstak', 'https://compstak.com'],
  ['Disney Streaming Services', 'https://disneyplus.com'],
  ['Dripower LTD', 'https://zd.drip.im'],
  ['E.ON', 'https://www.eon.com'],
  ['Eloquentix', 'https://eloquentix.com/'],
  ['Evolution Gaming', 'https://eng.evolutiongaming.com/'],
  ['Exelonix', 'https://exelonix.com/'],
  ['Gemini Observatory', 'https://www.gemini.edu'],
  ['Grieg Connect', 'https://www.griegconnect.com'],
  ['Hi.Fi', 'https://hi.fi'],
  ['ING Bank', 'https://www.ing.com'],
  ['Inner Product', 'https://inner-product.com'],
  ['Intent HQ', 'https://www.intenthq.com/'],
  ['ITV', 'https://www.itv.com/'],
  ['Kaluza', 'https://www.kaluza.com'],
  ['Klarna', 'https://www.klarna.com'],
  ['Medidata', 'https://www.medidata.com/'],
  ['Moda Operandi', 'https://www.modaoperandi.com/'],
  ['Ocado Technology', 'https://ocadotechnology.com/'],
  ['OVO Energy', 'https://www.ovoenergy.com'],
  ['Packlink', 'https://www.packlink.com'],
  ['Philips', 'https://www.philips.com/'],
  ['Precog', 'https://precog.com'],
  ['Prezi', 'https://prezi.com'],
  ['RMS', 'https://www.rms.com/'],
  ['Revonte', 'https://revonte.com'],
  ['Rewards Network', 'https://www.rewardsnetwork.com/'],
  ['Security Scorecard', 'https://www.securityscorecard.io/'],
  ['ShipReq', 'https://shipreq.com'],
  ['Software Mill', 'https://softwaremill.com'],
  ['SpotX', 'https://www.spotx.tv/'],
  ['Standard Chartered', 'https://www.sc.com'],
  ['SWOP', 'https://swop.cx/'],
  ['Tapad', 'https://www.tapad.com/'],
  ['Tenable', 'https://www.tenable.com'],
  ['Tinkoff', 'https://www.tinkoff.ru/eng/'],
  ['vidIQ', 'https://vidiq.com/'],
  ['XITE', 'https://xite.com/'],
  ['Whisk', 'https://whisk.com'],
  ['Zendesk', 'https://www.zendesk.com'],
];

function Users(props) {
  const renderUsers = users.map((user, i) => (
    <li key={i}>
      <a href={user[1]}>{user[0]}</a>
    </li>
  ));
  return (
    <div className="docMainWrapper wrapper">
      <Container className="mainContainer versionsContainer">
        <div className="post">
          <header className="postHeader">
            <h1>Users</h1>
          </header>

          <p>
            Cats Effect has been deployed at scale for years by countless companies covering nearly
            every segment of the industry. If your company is using Cats Effect and you don't see it
            listed here, feel free to <a href="https://github.com/typelevel/cats-effect/edit/docs/website/pages/en/users.js">add it!</a>
          </p>

          <ul>
            {renderUsers}
          </ul>

        </div>
      </Container>
    </div>
  );
}

module.exports = Users;
