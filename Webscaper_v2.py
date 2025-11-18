
import requests
from bs4 import BeautifulSoup
import sqlite3
import argparse
import sys

def fetch_addresses_from_db(db_path):
    """
    Fetches addresses from the specified SQLite database.

    Args:
        db_path (str): The path to the SQLite database.

    Returns:
        list: A list of addresses.
    """
    try:
        conn = sqlite3.connect(db_path)
        cursor = conn.cursor()
        cursor.execute("SELECT address FROM addresses")
        addresses = [row[0] for row in cursor.fetchall()]
        conn.close()
        return addresses
    except sqlite3.OperationalError as e:
        print(f"Error connecting to or querying the database: {e}", file=sys.stderr)
        sys.exit(1)

def scrape_website(url, address, headers):
    """
    Scrapes a website for a given address.

    Args:
        url (str): The URL of the website to scrape.
        address (str): The address to search for.
        headers (dict): The headers to use for the request.

    Returns:
        BeautifulSoup: The parsed HTML content of the page, or None if the request fails.
    """
    params = {'q': address}
    try:
        response = requests.get(url, params=params, headers=headers)
        response.raise_for_status()  # Raise an exception for bad status codes
        return BeautifulSoup(response.content, 'html.parser')
    except requests.exceptions.RequestException as e:
        print(f"Failed to retrieve the webpage for address: {address}. Error: {e}", file=sys.stderr)
        return None

def extract_results(soup):
    """
    Extracts search results from the parsed HTML.

    Args:
        soup (BeautifulSoup): The parsed HTML content.

    Yields:
        tuple: A tuple containing the title and link of a search result.
    """
    results = soup.find_all('div', class_='result-class')
    if not results:
        print("No results found. The website structure might have changed.", file=sys.stderr)
        return

    for result in results:
        try:
            title = result.find('h2').text
            link = result.find('a')['href']
            yield title, link
        except (AttributeError, TypeError) as e:
            print(f"Error extracting data from a result: {e}", file=sys.stderr)
            continue

def main():
    """
    Main function to orchestrate the web scraping process.
    """
    parser = argparse.ArgumentParser(description="Scrape a website for addresses.")
    parser.add_argument("db_path", help="Path to the SQLite database containing addresses.")
    parser.add_argument("--url", default="https://example.com/search", help="URL of the website to scrape.")
    args = parser.parse_args()

    headers = {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.3'
    }

    addresses = fetch_addresses_from_db(args.db_path)

    for address in addresses:
        soup = scrape_website(args.url, address, headers)
        if soup:
            print(f"Results for address: {address}")
            for title, link in extract_results(soup):
                print(f"  Title: {title}")
                print(f"  Link: {link}")
            print("-" * 50)

if __name__ == "__main__":
    main()
